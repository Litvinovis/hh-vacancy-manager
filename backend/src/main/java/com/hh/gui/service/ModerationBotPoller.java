package com.hh.gui.service;

import com.hh.gui.config.FeatureFlags;
import com.hh.gui.util.HttpUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * Receives the ✅/❌ button taps from ModerationService's cards via long-polling on the
 * PERSONAL bot (app.telegram.bot-token — the owner's own DM, not the channel bot) —
 * same getUpdates approach as TelegramBotPoller, same reason (no confirmed public HTTPS
 * reachability for a webhook), separate poller because it's a different bot identity
 * and a different update type (callback_query, not message).
 *
 * Entirely inert while FeatureFlags.moderationEnabled is false — see start().
 */
@Component
public class ModerationBotPoller {

    private static final Logger log = LoggerFactory.getLogger(ModerationBotPoller.class);
    private static final String GET_UPDATES_URL = "https://api.telegram.org/bot%s/getUpdates?offset=%d&timeout=30";

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    private final FeatureFlags featureFlags;
    private final ModerationService moderationService;
    private final TelegramNotifier telegramNotifier;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running;
    // In-memory only, same tradeoff as TelegramBotPoller.offset — a re-delivered update
    // after a restart just re-runs an already-applied decision (markModerationApproved/
    // Rejected are idempotent UPDATEs), harmless.
    private volatile long offset = 0;

    public ModerationBotPoller(FeatureFlags featureFlags, ModerationService moderationService,
                                TelegramNotifier telegramNotifier) {
        this.featureFlags = featureFlags;
        this.moderationService = moderationService;
        this.telegramNotifier = telegramNotifier;
    }

    @PostConstruct
    void start() {
        if (!featureFlags.isModerationEnabled()) {
            log.info("Модерация отключена (app.moderation.enabled=false) — поллер не запущен");
            return;
        }
        if (botToken == null || botToken.isBlank()) {
            log.warn("Модерация включена, но TELEGRAM_BOT_TOKEN не задан — поллер не запущен");
            return;
        }
        running = true;
        Thread.ofVirtual().name("moderation-bot-poller").start(this::pollLoop);
        log.info("Поллер модерации запущен (long-polling)");
    }

    @PreDestroy
    void stop() {
        running = false;
    }

    private void pollLoop() {
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                log.error("Ошибка опроса поллера модерации: {}", e.getMessage(), e);
                sleepQuietly(5000);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void poll() throws Exception {
        String url = String.format(GET_UPDATES_URL, botToken, offset);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(35_000);

        int code = conn.getResponseCode();
        String body = HttpUtil.readBody(conn, code);
        if (code != 200) {
            log.warn("getUpdates (модерация) вернул {}: {}", code, body);
            sleepQuietly(5000);
            return;
        }

        Map<?, ?> response = mapper.readValue(body, Map.class);
        if (!(response.get("result") instanceof List<?> updates)) return;

        for (Object updateObj : updates) {
            if (!(updateObj instanceof Map<?, ?> update)) continue;
            if (!(update.get("update_id") instanceof Number updateIdNum)) {
                log.warn("Update без числового update_id, пропускаем: {}", update);
                continue;
            }
            offset = updateIdNum.longValue() + 1;
            try {
                handleUpdate(update);
            } catch (Exception e) {
                log.error("Не удалось обработать update модерации: {}", e.getMessage(), e);
            }
        }
    }

    private void handleUpdate(Map<?, ?> update) {
        if (!(update.get("callback_query") instanceof Map<?, ?> callback)) return;
        if (!(callback.get("id") instanceof String callbackQueryId)) return;
        if (!(callback.get("data") instanceof String data)) return;

        telegramNotifier.answerCallbackQuery(callbackQueryId);

        Long vacancyId = parseVacancyId(data);
        if (vacancyId == null) {
            log.warn("Нераспознанный callback_data модерации: {}", data);
            return;
        }
        if (data.startsWith("modpub:")) {
            moderationService.resolveApprove(vacancyId);
        } else if (data.startsWith("modrej:")) {
            moderationService.resolveReject(vacancyId);
        } else {
            log.warn("Нераспознанный callback_data модерации: {}", data);
        }
    }

    /** "modpub:123" / "modrej:123" → 123L; null on anything else (unknown prefix, missing
     *  or non-numeric id) — a malformed callback_data must never throw and kill the update. */
    static Long parseVacancyId(String callbackData) {
        if (callbackData == null) return null;
        int colon = callbackData.indexOf(':');
        if (colon < 0) return null;
        try {
            return Long.parseLong(callbackData.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
