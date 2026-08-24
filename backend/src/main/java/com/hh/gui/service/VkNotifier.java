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
import java.util.Map;

/**
 * Posts to a VK community wall via the VK API (wall.post) — the VK-side twin of
 * TelegramNotifier.sendViaChannelBot. See ChannelPublisher for where this is called
 * from (right after a successful Telegram channel send, gated on RuntimeConfig.vkEnabled).
 */
@Component
public class VkNotifier {

    private static final Logger log = LoggerFactory.getLogger(VkNotifier.class);

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

    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    /**
     * Posts message to the wall as the community itself (from_group=1), never as
     * whichever admin's token is configured. Returns false on missing config, a
     * transport failure, or a VK API error object in the response body — VK answers
     * HTTP 200 even for API-level failures (bad permissions, banned word, rate limit),
     * so the body has to be parsed either way to tell success from failure.
     */
    public boolean post(String message) {
        if (accessToken == null || accessToken.isEmpty()) {
            log.warn("VK access token не настроен (app.vk.access-token) — пост в VK не отправлен");
            return false;
        }
        if (groupId == null || groupId.isBlank()) {
            log.warn("VK group id не настроен (app.vk.group-id) — пост в VK не отправлен");
            return false;
        }
        try {
            String body = "owner_id=" + URLEncoder.encode("-" + groupId, StandardCharsets.UTF_8)
                + "&from_group=1"
                + "&message=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
                + "&v=" + URLEncoder.encode(apiVersion, StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiBaseUrl + "/method/wall.post").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String respBody = HttpUtil.readBody(conn, code);
            if (code != 200) {
                log.error("Ошибка VK API {}: {}", code, respBody);
                return false;
            }
            if (hasError(respBody)) {
                log.error("VK API вернул ошибку: {}", respBody);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Не удалось отправить пост в VK: {}", e.getMessage());
            return false;
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
