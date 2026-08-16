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

/**
 * Sends messages via Telegram Bot API.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    // Overridable so tests can point this at a local HttpServer instead of the real
    // Bot API — same pattern TelegramClient already uses for app.tgscraper.url.
    @Value("${app.telegram.api-base-url:https://api.telegram.org}")
    private String apiBaseUrl;

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.chat-id:}")
    private String chatId;

    // Separate bot for the public channel/paid-subscriber sends (see sendViaChannelBot) —
    // deliberately its own credential, not a fallback of botToken above, so the public
    // channel and personal family notifications never share a bot identity.
    @Value("${app.telegram.channel-bot-token:}")
    private String channelBotToken;

    public boolean send(String message) {
        return send(message, null);
    }

    /**
     * @param targetChatId overrides the configured default chat (e.g. a search's own
     *                      Telegram channel) — null or blank falls back to app.telegram.chat-id.
     */
    public boolean send(String message, String targetChatId) {
        String resolvedChatId = targetChatId != null && !targetChatId.isBlank() ? targetChatId : chatId;
        return doSend(botToken, "Токен Telegram-бота не настроен", message, resolvedChatId);
    }

    /**
     * Sends via the dedicated channel bot (app.telegram.channel-bot-token) instead of the
     * personal one — used for public posts/broadcasts (public-format sends, delayed
     * publish, subscriber fan-out). No fallback chat id: a channel/subscriber destination
     * is always explicit, unlike send()'s personal-report default.
     */
    public boolean sendViaChannelBot(String message, String targetChatId) {
        return doSend(channelBotToken, "Токен канального Telegram-бота не настроен (app.telegram.channel-bot-token)",
            message, targetChatId);
    }

    /**
     * Current subscriber count of a channel the channel bot is admin of (Bot API
     * getChatMemberCount) — null on any failure (missing token, network error, bot not
     * an admin there), never an exception; the caller (a periodic metrics poll) should
     * just skip that channel this tick rather than fail the whole sweep over one.
     */
    public Integer getChatMemberCount(String targetChatId) {
        if (channelBotToken == null || channelBotToken.isEmpty()) return null;
        if (targetChatId == null || targetChatId.isBlank()) return null;
        try {
            String url = apiBaseUrl + "/bot" + channelBotToken + "/getChatMemberCount?chat_id="
                + URLEncoder.encode(targetChatId, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);
            if (code != 200) {
                log.warn("getChatMemberCount({}) вернул {}: {}", targetChatId, code, body);
                return null;
            }
            var json = new tools.jackson.databind.ObjectMapper().readTree(body);
            return json.path("ok").asBoolean(false) ? json.path("result").asInt() : null;
        } catch (Exception e) {
            log.warn("Не удалось получить число подписчиков для {}: {}", targetChatId, e.getMessage());
            return null;
        }
    }

    /**
     * Public @username of a channel the channel bot is admin of (Bot API getChat) — null
     * if the channel has none (fully private, no public link) or on any failure. Needed
     * because tg-scraper's web-client sidecar reads channels by username, not numeric
     * chat_id (see VacancyPipelineService.checkOwnChannelEngagement).
     */
    public String getChatUsername(String targetChatId) {
        if (channelBotToken == null || channelBotToken.isEmpty()) return null;
        if (targetChatId == null || targetChatId.isBlank()) return null;
        try {
            String url = apiBaseUrl + "/bot" + channelBotToken + "/getChat?chat_id="
                + URLEncoder.encode(targetChatId, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);
            if (code != 200) {
                log.warn("getChat({}) вернул {}: {}", targetChatId, code, body);
                return null;
            }
            var json = new tools.jackson.databind.ObjectMapper().readTree(body);
            if (!json.path("ok").asBoolean(false)) return null;
            String username = json.path("result").path("username").asText(null);
            return username != null && !username.isBlank() ? username : null;
        } catch (Exception e) {
            log.warn("Не удалось получить username канала для {}: {}", targetChatId, e.getMessage());
            return null;
        }
    }

    private boolean doSend(String token, String missingTokenMessage, String message, String resolvedChatId) {
        if (token == null || token.isEmpty()) {
            log.warn(missingTokenMessage);
            return false;
        }
        if (resolvedChatId == null || resolvedChatId.isEmpty()) {
            log.warn("ID чата Telegram не настроен");
            return false;
        }

        try {
            String url = apiBaseUrl + "/bot" + token + "/sendMessage";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            String body = "chat_id=" + URLEncoder.encode(resolvedChatId, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&parse_mode=HTML&disable_web_page_preview=true";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                log.info("Сообщение Telegram успешно отправлено");
                return true;
            } else {
                log.error("Ошибка Telegram API {}: {}", code, HttpUtil.readBody(conn, code));
                return false;
            }
        } catch (Exception e) {
            log.error("Не удалось отправить сообщение Telegram: {}", e.getMessage());
            return false;
        }
    }
}
