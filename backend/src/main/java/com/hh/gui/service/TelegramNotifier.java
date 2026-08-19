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
     * chat_id (see ChannelEngagementTracker.checkOwnChannels).
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

    /**
     * A moderation card: sent via the CHANNEL bot (app.telegram.channel-bot-token), not
     * the personal one — the personal bot's token is shared with an unrelated system
     * (hermes-agent) that already long-polls it, and two independent getUpdates
     * consumers on one token fight over it (live-observed: persistent 409 "terminated by
     * other getUpdates request"). The channel bot's own poller (TelegramBotPoller) only
     * runs when app.subscriptions.enabled — off today — so it's a free slot for now;
     * turning subscriptions on later would recreate the exact same conflict between the
     * two, at which point one of them needs its own dedicated bot token instead.
     * Still targets app.telegram.chat-id (the owner's personal DM), not the channel —
     * only the BOT IDENTITY sending it changed, not the destination. Two inline buttons
     * whose callback_data ModerationBotPoller dispatches back to ModerationService.
     * Unlike doSend, this can't go through the plain sendMessage form body —
     * reply_markup is JSON, not a flat field.
     */
    /**
     * Sends a moderation card for a batch of one or more vacancies grouped into one
     * message (see ModerationService.formatBatchCard) — one ✅/❌ row per vacancy, plus
     * one extra "✅ Одобрить всё" row when there's more than one (pointless on a batch
     * of one — that's just the individual button again). A single-vacancy batch is
     * exactly what used to be the whole "one card per vacancy" shape, so there's no
     * separate single-card method any more — this covers both.
     */
    public boolean sendModerationCardBatch(String message, java.util.List<Long> vacancyIds) {
        StringBuilder rows = new StringBuilder();
        for (Long id : vacancyIds) {
            if (!rows.isEmpty()) rows.append(",");
            rows.append("[{\"text\":\"✅ Опубликовать\",\"callback_data\":\"modpub:").append(id).append("\"},")
                .append("{\"text\":\"❌ Отклонить\",\"callback_data\":\"modrej:").append(id).append("\"}]");
        }
        if (vacancyIds.size() > 1) {
            if (!rows.isEmpty()) rows.append(",");
            rows.append("[{\"text\":\"✅ Одобрить всё (").append(vacancyIds.size()).append(")\",")
                .append("\"callback_data\":\"modpuball\"}]");
        }
        String replyMarkup = "{\"inline_keyboard\":[" + rows + "]}";
        return sendModerationMessage(message, replyMarkup);
    }

    private boolean sendModerationMessage(String message, String replyMarkup) {
        if (channelBotToken == null || channelBotToken.isEmpty()) {
            log.warn("Токен канального Telegram-бота не настроен — карточка модерации не отправлена");
            return false;
        }
        if (chatId == null || chatId.isBlank()) {
            log.warn("app.telegram.chat-id не настроен — некуда отправить карточку модерации");
            return false;
        }
        try {
            String url = apiBaseUrl + "/bot" + channelBotToken + "/sendMessage";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&parse_mode=HTML&disable_web_page_preview=true"
                + "&reply_markup=" + URLEncoder.encode(replyMarkup, StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) return true;
            log.error("Ошибка отправки карточки модерации {}: {}", code, HttpUtil.readBody(conn, code));
            return false;
        } catch (Exception e) {
            log.error("Не удалось отправить карточку модерации: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Removes a resolved moderation card entirely, rather than leaving its ✅/❌ buttons
     * sitting there clickable forever (Telegram doesn't grey out inline buttons on its
     * own just because the vacancy was already decided). Same bot as sendModerationCardBatch —
     * only the bot that sent a message may delete it. Best-effort: a failure here is a
     * cosmetic leftover, not a reason to fail the decision that already went through.
     */
    public void deleteModerationCard(long messageId) {
        if (channelBotToken == null || channelBotToken.isEmpty()) return;
        if (chatId == null || chatId.isBlank()) return;
        try {
            String url = apiBaseUrl + "/bot" + channelBotToken + "/deleteMessage?chat_id="
                + URLEncoder.encode(chatId, StandardCharsets.UTF_8) + "&message_id=" + messageId;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) log.warn("deleteMessage({}) вернул {}: {}", messageId, code, HttpUtil.readBody(conn, code));
        } catch (Exception e) {
            log.warn("Не удалось удалить карточку модерации: {}", e.getMessage());
        }
    }

    /** Stops the loading spinner on the button the admin just tapped — Telegram shows it
     *  indefinitely otherwise. Best-effort: a failure here doesn't undo the decision, which
     *  has already been applied by the time this is called. */
    public void answerCallbackQuery(String callbackQueryId) {
        if (channelBotToken == null || channelBotToken.isEmpty()) return;
        try {
            String url = apiBaseUrl + "/bot" + channelBotToken + "/answerCallbackQuery?callback_query_id="
                + URLEncoder.encode(callbackQueryId, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) log.warn("answerCallbackQuery вернул {}: {}", code, HttpUtil.readBody(conn, code));
        } catch (Exception e) {
            log.warn("Не удалось подтвердить callback_query: {}", e.getMessage());
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
