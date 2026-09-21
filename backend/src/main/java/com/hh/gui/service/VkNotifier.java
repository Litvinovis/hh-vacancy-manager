package com.hh.gui.service;

import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Posts to a VK community wall via the VK API (wall.post) — the VK-side twin of
 * TelegramNotifier.sendViaChannelBot. See ChannelPublisher for where this is called
 * from (right after a successful Telegram channel send, gated on RuntimeConfig.vkEnabled).
 */
@Component
public class VkNotifier {

    private static final Logger log = LoggerFactory.getLogger(VkNotifier.class);
    /** Сколько раз грузить PNG на upload_url, если VK отвечает пустым photo. */
    private static final int UPLOAD_ATTEMPTS = 2;

    @Value("${app.vk.api-base-url:https://api.vk.com}")
    private String apiBaseUrl;

    @Value("${app.vk.access-token:}")
    private String accessToken;

    // Positive numeric community id as configured (vk.com/club<id>) — negated below for
    // wall.post's owner_id, VK's own convention for "this id is a community, not a user".
    @Value("${app.vk.group-id:}")
    private String groupId;

    @Value("${app.vk.api-version:5.199}")
    private String apiVersion;

    /** См. application.yml — статический пользовательский токен под загрузку картинок (запасной вариант). */
    @Value("${app.vk.photo-upload-token:}")
    private String photoUploadToken;
    /** Основной источник пользовательского токена — VK ID с автообновлением; null в тестах. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VkIdTokenService vkIdTokens;
    /** Причина, по которой карточки не грузятся, пишется в лог один раз, а не на каждом посте. */
    private volatile boolean uploadUnavailableLogged;

    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    /**
     * Posts message to the wall as the community itself (from_group=1), never as
     * whichever admin's token is configured. Returns false on missing config, a
     * transport failure, or a VK API error object in the response body — VK answers
     * HTTP 200 even for API-level failures (bad permissions, banned word, rate limit),
     * so the body has to be parsed either way to tell success from failure.
     */
    public boolean post(String message) {
        return postReturningId(message) != null;
    }

    /**
     * wall.post; возвращает id созданного поста или null, если не отправлено. Id нужен
     * очереди VK: по нему под постом создаётся первый комментарий со ссылкой на отклик
     * (см. {@link #comment}) и пост потом можно найти или удалить.
     */
    public Long postReturningId(String message) {
        return postReturningId(message, null);
    }

    /**
     * Первый комментарий под постом от имени сообщества. Ссылку на отклик держим здесь, а не
     * в тексте: внешние ссылки в самом посте режут охват в умной ленте (разборы 2026), а
     * комментарий под постом ещё и сам по себе засчитывается как активность.
     */
    public boolean comment(long postId, String text) {
        if (!configured()) return false;
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("owner_id", "-" + groupId);
        params.put("post_id", String.valueOf(postId));
        params.put("from_group", groupId);
        params.put("message", text);
        return call("wall.createComment", params) != null;
    }

    /**
     * Пост с опросом: polls.create от имени сообщества, затем wall.post с вложением poll.
     * Возвращает id поста или null.
     */
    public Long postPoll(String message, String question, List<String> options) {
        return postPoll(message, question, options, null);
    }

    public Long postPoll(String message, String question, List<String> options, String extraAttachment) {
        if (!configured()) return null;
        Map<String, String> p = new java.util.LinkedHashMap<>();
        p.put("owner_id", "-" + groupId);
        p.put("question", question);
        p.put("is_anonymous", "1");
        try {
            p.put("add_answers", mapper.writeValueAsString(options));
        } catch (Exception e) {
            log.error("Не удалось сериализовать варианты опроса: {}", e.getMessage());
            return null;
        }
        Map<?, ?> created = call("polls.create", p);
        if (created == null) return null;
        Map<?, ?> poll = (Map<?, ?>) created.get("response");
        Object pollId = poll.get("id");
        Object ownerId = poll.get("owner_id");
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("owner_id", "-" + groupId);
        params.put("from_group", "1");
        params.put("message", message);
        String attachments = "poll" + ownerId + "_" + pollId;
        if (extraAttachment != null && !extraAttachment.isBlank()) attachments += "," + extraAttachment;
        params.put("attachments", attachments);
        Map<?, ?> resp = call("wall.post", params);
        if (resp == null) return null;
        Object postId = ((Map<?, ?>) resp.get("response")).get("post_id");
        return postId instanceof Number n ? n.longValue() : null;
    }

    /**
     * Загружает PNG на стену сообщества: photos.getWallUploadServer → multipart POST →
     * photos.saveWallPhoto. Возвращает attachment-строку «photo{owner}_{id}» для wall.post
     * или null, если что-то не вышло — тогда пост уйдёт без картинки, а не не уйдёт вовсе.
     */
    public String uploadWallPhoto(byte[] png, String fileName) {
        if (!configured()) return null;
        String uploadToken = userToken();
        if (uploadToken == null) {
            if (!uploadUnavailableLogged) {
                log.warn("Карточки к постам VK отключены: нет пользовательского токена (VK ID через scripts/vk-id-auth.py " +
                    "или VK_PHOTO_UPLOAD_TOKEN с правами photos, wall — групповой загружать картинки не умеет). Посты уходят текстом.");
                uploadUnavailableLogged = true;
            }
            return null;
        }
        try {
            Map<String, String> p = new java.util.LinkedHashMap<>();
            p.put("group_id", groupId);
            Map<?, ?> server = callWithToken("photos.getWallUploadServer", p, uploadToken);
            if (server == null) return null;
            String uploadUrl = String.valueOf(((Map<?, ?>) server.get("response")).get("upload_url"));

            Map<?, ?> uploaded = uploadPngWithRetry(uploadUrl, png, fileName);
            if (uploaded == null) return null;

            Map<String, String> save = new java.util.LinkedHashMap<>();
            save.put("group_id", groupId);
            save.put("photo", String.valueOf(uploaded.get("photo")));
            save.put("server", String.valueOf(uploaded.get("server")));
            save.put("hash", String.valueOf(uploaded.get("hash")));
            Map<?, ?> saved = callWithToken("photos.saveWallPhoto", save, uploadToken);
            if (saved == null) return null;
            Map<?, ?> photo = (Map<?, ?>) ((List<?>) saved.get("response")).get(0);
            return "photo" + photo.get("owner_id") + "_" + photo.get("id");
        } catch (Exception e) {
            log.error("Не удалось загрузить картинку в VK: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Сервер загрузки VK изредка отвечает 200 с пустым полем photo («"photo":""» или «[]»),
     * и следующий за этим photos.saveWallPhoto падает с error 100 «photo is undefined»
     * (наблюдалось 21.09.2026, пост ушёл без карточки). Повторная загрузка того же PNG
     * проходит, поэтому при пустом photo пробуем ещё раз и логируем сырой ответ —
     * чтобы в следующий раз было видно, что именно вернул VK.
     */
    private Map<?, ?> uploadPngWithRetry(String uploadUrl, byte[] png, String fileName) throws Exception {
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            String body = uploadPng(uploadUrl, png, fileName);
            if (body == null) return null;
            Map<?, ?> uploaded = mapper.readValue(body, Map.class);
            if (hasPhoto(uploaded)) return uploaded;
            log.warn("VK upload вернул пустое photo (попытка {}/{}): {}", attempt, UPLOAD_ATTEMPTS, body);
        }
        return null;
    }

    static boolean hasPhoto(Map<?, ?> uploaded) {
        Object photo = uploaded.get("photo");
        if (photo == null) return false;
        String s = String.valueOf(photo).trim();
        return !s.isEmpty() && !s.equals("[]") && !s.equals("null");
    }

    /** multipart POST на upload_url; тело ответа VK или null, если HTTP-статус не 200. */
    private String uploadPng(String uploadUrl, byte[] png, String fileName) throws Exception {
        String boundary = "----hhgui" + System.nanoTime();
        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"" + fileName
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(png);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = HttpUtil.readBody(conn, code);
        if (code != 200) {
            log.error("VK upload вернул {}: {}", code, body);
            return null;
        }
        return body;
    }

    /** wall.post с вложениями (attachments через запятую); null — не отправлено. */
    public Long postReturningId(String message, String attachments) {
        if (!configured()) return null;
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("owner_id", "-" + groupId);
        params.put("from_group", "1");
        params.put("message", message);
        if (attachments != null && !attachments.isBlank()) params.put("attachments", attachments);
        Map<?, ?> resp = call("wall.post", params);
        if (resp == null) return null;
        Object postId = ((Map<?, ?>) resp.get("response")).get("post_id");
        return postId instanceof Number n ? n.longValue() : null;
    }

    /** Короткое имя сообщества (vk.com/<имя>) — для сообщественного хэштега #тег@имя. Кэшируется. */
    public String screenName() {
        if (cachedScreenName != null) return cachedScreenName;
        if (!configured()) return null;
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("group_id", groupId);
        Map<?, ?> resp = call("groups.getById", params);
        if (resp == null) return null;
        try {
            Object r = resp.get("response");
            Object groups = r instanceof Map<?, ?> m ? m.get("groups") : r;
            Map<?, ?> g = (Map<?, ?>) ((List<?>) groups).get(0);
            cachedScreenName = String.valueOf(g.get("screen_name"));
        } catch (Exception e) {
            log.warn("Не удалось разобрать screen_name сообщества VK: {}", e.getMessage());
        }
        return cachedScreenName;
    }

    private volatile String cachedScreenName;

    private boolean configured() {
        if (accessToken == null || accessToken.isEmpty()) {
            log.warn("VK access token не настроен (app.vk.access-token) — пост в VK не отправлен");
            return false;
        }
        if (groupId == null || groupId.isBlank()) {
            log.warn("VK group id не настроен (app.vk.group-id) — пост в VK не отправлен");
            return false;
        }
        return true;
    }

    /** Пользовательский токен: живой из VK ID, иначе статический из .env, иначе null. */
    private String userToken() {
        if (vkIdTokens != null) {
            var live = vkIdTokens.accessToken();
            if (live.isPresent()) return live.get();
        }
        return photoUploadToken == null || photoUploadToken.isBlank() ? null : photoUploadToken;
    }

    void setVkIdTokens(VkIdTokenService vkIdTokens) { this.vkIdTokens = vkIdTokens; }

    /** POST к методу VK API; null при любой ошибке (сетевой, HTTP или в теле ответа). */
    private Map<?, ?> call(String method, Map<String, String> params) {
        return callWithToken(method, params, accessToken);
    }

    private Map<?, ?> callWithToken(String method, Map<String, String> params, String token) {
        try {
            StringBuilder body = new StringBuilder();
            for (var e : params.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            body.append("&access_token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8))
                .append("&v=").append(URLEncoder.encode(apiVersion, StandardCharsets.UTF_8));
            HttpURLConnection conn = (HttpURLConnection) new URL(apiBaseUrl + "/method/" + method).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String respBody = HttpUtil.readBody(conn, code);
            if (code != 200) {
                log.error("Ошибка VK API {} ({}): {}", code, method, respBody);
                return null;
            }
            Map<?, ?> parsed = mapper.readValue(respBody, Map.class);
            if (parsed.containsKey("error")) {
                log.error("VK API {} вернул ошибку: {}", method, respBody);
                return null;
            }
            return parsed;
        } catch (Exception e) {
            log.error("Не удалось вызвать VK API {}: {}", method, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasError(String respBody) {
        try {
            Map<?, ?> parsed = mapper.readValue(respBody, Map.class);
            return parsed.containsKey("error");
        } catch (Exception e) {
            // Body wasn't valid JSON at all — treat as failure rather than assume success.
            return true;
        }
    }
}
