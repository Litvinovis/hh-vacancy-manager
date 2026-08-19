package com.hh.gui.client;

import tools.jackson.databind.ObjectMapper;
import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for the tg-scraper sidecar (tg-scraper/server.js) — a headless-browser
 * session logged into web.telegram.org as a real account, used to read public
 * Telegram channels as an additional vacancy source alongside hh.ru.
 *
 * Why a browser session instead of the Bot API or a Telethon/MTProto userbot:
 * a Bot API bot only receives posts from channels where it's been added as
 * admin (won't happen for channels this account doesn't own), and any MTProto
 * client — Telethon, Pyrogram, even TDLib — requires registering an app at
 * my.telegram.org, which was unreachable for this deployment. web.telegram.org
 * ships its own embedded app credentials, so logging in only needs the normal
 * phone+code flow a person goes through.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    @Value("${app.tgscraper.url:http://127.0.0.1:8096}")
    private String tgScraperBaseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    /** @param views total view count at scrape time, null if unavailable (see collectMessages
     *                in tg-scraper/server.js — read via Telegram Web K's own internal message
     *                cache, best-effort and can legitimately be absent).
     *  @param reactions emoji -> count, empty map if none; same best-effort caveat as views. */
    public record TelegramMessage(
        String id, String text, String publishedAt, String link, String channel, String source,
        Integer views, java.util.Map<String, Integer> reactions) {}

    public record ChannelResult(boolean ok, String reason, List<TelegramMessage> items) {
        static ChannelResult failure(String reason) {
            return new ChannelResult(false, reason, List.of());
        }
    }

    // "channel_not_found" means the sidecar's search box returned no matching row
    // within its fixed wait (see tg-scraper/server.js openChannel) — that's a UI
    // timing signal, not proof the channel is actually gone, so it's worth a couple
    // of quick retries before treating it as a real failure for this tick.
    private static final int NOT_FOUND_RETRIES = 2;
    private static final long NOT_FOUND_RETRY_DELAY_MS = 3000;

    /**
     * Fetches up to {@code limit} of the most recent posts from a public channel.
     * A read timeout well above the connect timeout: the sidecar scrolls the
     * (virtualized) message list to load history, which for a busy channel can
     * take longer than a typical HTTP client default.
     */
    public ChannelResult fetchChannel(String username, int limit) {
        ChannelResult result = fetchChannelOnce(username, limit);
        for (int attempt = 1; attempt <= NOT_FOUND_RETRIES && !result.ok() && "channel_not_found".equals(result.reason()); attempt++) {
            log.debug("Telegram-канал @{}: channel_not_found, повтор {}/{}", username, attempt, NOT_FOUND_RETRIES);
            try {
                Thread.sleep(NOT_FOUND_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = fetchChannelOnce(username, limit);
        }
        return result;
    }

    private ChannelResult fetchChannelOnce(String username, int limit) {
        try {
            String url = tgScraperBaseUrl + "/channel?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&limit=" + limit;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);

            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);
            if (!Boolean.TRUE.equals(json.get("ok"))) {
                String reason = String.valueOf(json.getOrDefault("reason", "unknown"));
                log.warn("Чтение Telegram-канала @{} не удалось: {}", username, reason);
                return ChannelResult.failure(reason);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) json.getOrDefault("items", List.of());
            List<TelegramMessage> items = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                items.add(new TelegramMessage(
                    str(item.get("id")), str(item.get("text")), str(item.get("publishedAt")),
                    str(item.get("link")), str(item.get("channel")), str(item.get("source")),
                    views(item.get("views")), reactions(item.get("reactions"))));
            }
            log.debug("Telegram-канал @{}: {} сообщений", username, items.size());
            return new ChannelResult(true, null, items);
        } catch (Exception e) {
            log.error("Ошибка обращения к tg-scraper для канала @{}: {}", username, e.getMessage());
            return ChannelResult.failure("client_error: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static Integer views(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Integer> reactions(Object o) {
        if (!(o instanceof Map)) return java.util.Map.of();
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (var e : ((Map<String, Object>) o).entrySet()) {
            if (e.getValue() instanceof Number n) result.put(e.getKey(), n.intValue());
        }
        return result;
    }
}
