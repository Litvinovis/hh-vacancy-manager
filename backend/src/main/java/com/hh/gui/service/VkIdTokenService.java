package com.hh.gui.service;

import com.hh.gui.util.HttpUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Пользовательский токен VK через VK ID (OAuth 2.1). Ответ VK на вопрос «как получить
 * бессрочный токен» в 2026 году — никак: у новых приложений `offline` отключён
 * («invalid scope», проверено 20.09.2026), access-token живёт час, обновляется
 * refresh-токеном, а refresh-токен меняется при каждом обновлении (старая пара
 * инвалидируется). Поэтому пара хранится не в .env, а в файле, который сервис
 * перезаписывает сам.
 *
 * Первичную авторизацию (PKCE, согласие пользователя в браузере) делает
 * scripts/vk-id-auth.py — он же пишет первый token-файл. Дальше всё здесь:
 * {@link #accessToken()} отдаёт живой токен (обновив при необходимости), а
 * {@link #refreshIfDue()} раз в 20 минут держит пару свежей, чтобы отказ VK
 * всплыл в логе и в Telegram владельца сразу, а не в момент публикации.
 *
 * Если VK ID не настроен (нет client-id или файла), сервис молчит и возвращает
 * пусто — потребители (VkNotifier, VkReaderClient) тогда используют статические
 * токены из .env, как раньше.
 */
@Component
public class VkIdTokenService {

    private static final Logger log = LoggerFactory.getLogger(VkIdTokenService.class);

    /** За сколько до истечения считаем токен «пора обновлять». */
    static final long REFRESH_AHEAD_SECONDS = 30 * 60;
    /** Меньше этого запаса токен уже не отдаём без попытки обновить. */
    static final long MIN_REMAINING_SECONDS = 5 * 60;

    private final String clientId;
    private final Path tokenFile;
    private final String authUrl;
    private final Clock clock;
    private final MeterRegistry registry;
    private final TelegramNotifier telegramNotifier;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile TokenSet tokens;
    private volatile boolean fileMissingLogged;
    private volatile boolean ownerAlerted;

    /** Пара токенов, как она лежит в файле. expiresAt — epoch-секунды. */
    public record TokenSet(String accessToken, String refreshToken, String deviceId, String state,
                           long expiresAt, long userId, String scope) {
        long remaining(Clock clock) { return expiresAt - clock.instant().getEpochSecond(); }
    }

    @Autowired
    public VkIdTokenService(@Value("${app.vk.id.client-id:}") String clientId,
                            @Value("${app.vk.id.token-file:${app.data-dir}/vk-id-token.json}") String tokenFile,
                            @Value("${app.vk.id.auth-url:https://id.vk.ru/oauth2/auth}") String authUrl,
                            MeterRegistry registry, TelegramNotifier telegramNotifier) {
        this(clientId, Path.of(tokenFile), authUrl, Clock.systemUTC(), registry, telegramNotifier);
    }

    VkIdTokenService(String clientId, Path tokenFile, String authUrl, Clock clock,
                     MeterRegistry registry, TelegramNotifier telegramNotifier) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.tokenFile = tokenFile;
        this.authUrl = authUrl;
        this.clock = clock;
        this.registry = registry;
        this.telegramNotifier = telegramNotifier;
        if (registry != null) {
            registry.gauge("vk_id_token_expires_in_seconds", this, s -> {
                TokenSet t = s.tokens;
                return t == null ? -1 : Math.max(0, t.remaining(s.clock));
            });
        }
    }

    public boolean configured() {
        return !clientId.isBlank();
    }

    /**
     * Живой access-token или пусто: VK ID не настроен, файла нет, либо обновить не
     * удалось и старый токен уже истёк. Просроченный токен не отдаём никогда — VK
     * ответит invalid_token, и вызывающий код спишет это на «ошибку API».
     */
    public Optional<String> accessToken() {
        if (!configured()) return Optional.empty();
        TokenSet t = current();
        if (t == null) return Optional.empty();
        if (t.remaining(clock) < MIN_REMAINING_SECONDS) {
            refresh();
            t = tokens;
        }
        return t != null && t.remaining(clock) > 0 ? Optional.of(t.accessToken()) : Optional.empty();
    }

    /** Плановое обновление: держим запас не меньше {@link #REFRESH_AHEAD_SECONDS}. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT20M")
    public void refreshIfDue() {
        if (!configured()) return;
        TokenSet t = current();
        if (t == null) return;
        if (t.remaining(clock) < REFRESH_AHEAD_SECONDS) refresh();
    }

    /** Текущая пара: из памяти, иначе с диска (файл мог обновить скрипт первичной авторизации). */
    private TokenSet current() {
        TokenSet t = tokens;
        if (t != null) return t;
        synchronized (this) {
            if (tokens == null) tokens = load();
            return tokens;
        }
    }

    /**
     * Обмен refresh → новая пара. Синхронизировано: две публикации подряд не должны
     * обменять один refresh-токен дважды — второй обмен VK отвергнет, а первая пара
     * к тому моменту уже перезаписана.
     */
    public synchronized boolean refresh() {
        TokenSet t = current();
        if (t == null) return false;
        String result;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", t.refreshToken());
            form.put("client_id", clientId);
            form.put("device_id", t.deviceId());
            form.put("state", t.state());
            Map<?, ?> resp = post(form);
            if (resp.containsKey("error")) {
                String error = String.valueOf(resp.get("error"));
                log.error("VK ID: обновление токена отклонено: {} — {}", error, resp.get("error_description"));
                result = "rejected";
                if (isFatal(error)) alertOwner(error + ": " + resp.get("error_description"));
            } else {
                long expiresIn = num(resp.get("expires_in"), 3600);
                TokenSet fresh = new TokenSet(
                    String.valueOf(resp.get("access_token")),
                    String.valueOf(resp.get("refresh_token")),
                    t.deviceId(), t.state(),
                    clock.instant().getEpochSecond() + expiresIn,
                    num(resp.get("user_id"), t.userId()),
                    resp.get("scope") == null ? t.scope() : String.valueOf(resp.get("scope")));
                save(fresh);
                tokens = fresh;
                ownerAlerted = false;
                log.info("VK ID: токен обновлён, действует {} мин (права: {})", expiresIn / 60, fresh.scope());
                result = "ok";
            }
        } catch (Exception e) {
            log.error("VK ID: обновление токена не удалось: {}", e.getMessage());
            result = "error";
        }
        if (registry != null) registry.counter("vk_id_token_refresh_total", "result", result).increment();
        return "ok".equals(result);
    }

    private static long num(Object v, long fallback) {
        return v instanceof Number n ? n.longValue() : fallback;
    }

    /** invalid_grant/invalid_token = refresh-токен отозван или протух; сам сервис уже не починится. */
    private static boolean isFatal(String error) {
        return "invalid_grant".equals(error) || "invalid_token".equals(error) || "invalid_client".equals(error);
    }

    private void alertOwner(String reason) {
        if (ownerAlerted || telegramNotifier == null) return;
        ownerAlerted = true;
        telegramNotifier.send("⚠️ VK ID: refresh-токен больше не принимается (" + reason + ").\n"
            + "Карточки к постам и чтение стены VK отвалятся, когда истечёт текущий токен. "
            + "Нужна повторная авторизация: scripts/vk-id-auth.py на сервере.");
    }

    private Map<?, ?> post(Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        for (var e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(authUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = HttpUtil.readBody(conn, code);
        Map<?, ?> parsed = resp.isBlank() ? Map.of() : mapper.readValue(resp, Map.class);
        if (code != 200 && !parsed.containsKey("error")) {
            throw new IOException("HTTP " + code + ": " + resp);
        }
        return parsed;
    }

    private TokenSet load() {
        if (!Files.exists(tokenFile)) {
            if (!fileMissingLogged) {
                log.warn("VK ID: файла с токенами нет ({}) — пользовательские методы VK работают только со статическими " +
                    "токенами из .env, если они заданы. Первичная авторизация: scripts/vk-id-auth.py", tokenFile);
                fileMissingLogged = true;
            }
            return null;
        }
        try {
            Map<?, ?> m = mapper.readValue(Files.readString(tokenFile), Map.class);
            return new TokenSet(
                String.valueOf(m.get("access_token")),
                String.valueOf(m.get("refresh_token")),
                String.valueOf(m.get("device_id")),
                m.get("state") == null ? "" : String.valueOf(m.get("state")),
                num(m.get("expires_at"), 0),
                num(m.get("user_id"), 0),
                m.get("scope") == null ? "" : String.valueOf(m.get("scope")));
        } catch (Exception e) {
            log.error("VK ID: не удалось прочитать {}: {}", tokenFile, e.getMessage());
            return null;
        }
    }

    /** Атомарно: временный файл рядом + move, чтобы упавший посреди записи процесс не оставил обрезанный JSON. */
    private void save(TokenSet t) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", t.accessToken());
        m.put("refresh_token", t.refreshToken());
        m.put("device_id", t.deviceId());
        m.put("state", t.state());
        m.put("expires_at", t.expiresAt());
        m.put("user_id", t.userId());
        m.put("scope", t.scope());
        m.put("refreshed_at", Instant.now(clock).toString());
        Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
        Files.createDirectories(tokenFile.toAbsolutePath().getParent());
        Files.writeString(tmp, mapper.writeValueAsString(m));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) { /* не POSIX — и ладно */ }
        Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Для тестов и диагностики. */
    TokenSet currentTokens() { return current(); }
}
