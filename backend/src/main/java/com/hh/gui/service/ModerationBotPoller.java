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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Receives the ✅/❌ button taps from ModerationService's cards via long-polling on the
 * CHANNEL bot (app.telegram.channel-bot-token), same getUpdates approach as
 * TelegramBotPoller, same reason (no confirmed public HTTPS reachability for a webhook).
 *
 * Deliberately NOT the personal bot (app.telegram.bot-token): that token is shared with
 * an unrelated system (hermes-agent) that already long-polls it, and Telegram allows
 * only one active getUpdates consumer per bot — live-observed as persistent 409
 * "terminated by other getUpdates request" once both polled it. The channel bot's own
 * poller (TelegramBotPoller) only runs when app.subscriptions.enabled, which is off
 * today, so this borrows an otherwise-idle slot — see the conflict check in start().
 * Cards still land in the owner's personal DM (app.telegram.chat-id) regardless of
 * which bot identity sent them — see TelegramNotifier.sendModerationCardBatch.
 *
 * Entirely inert while FeatureFlags.moderationEnabled is false — see start().
 */
@Component
public class ModerationBotPoller {

    private static final Logger log = LoggerFactory.getLogger(ModerationBotPoller.class);
    private static final String GET_UPDATES_URL = "https://api.telegram.org/bot%s/getUpdates?offset=%d&timeout=30";

    @Value("${app.telegram.channel-bot-token:}")
    private String channelBotToken;

    @Value("${app.data-dir}")
    private String dataDir;

    private final FeatureFlags featureFlags;
    private final ModerationService moderationService;
    private final TelegramNotifier telegramNotifier;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running;
    // Persisted to disk (see offsetFile/loadOffset/saveOffset) — UNLIKE
    // TelegramBotPoller.offset, which really is safe to lose on restart (its
    // command handlers are genuinely idempotent). This app redeploys many times a day;
    // an in-memory-only offset here resets on every one of them, and Telegram then
    // redelivers its whole backlog of not-yet-acknowledged callback_query taps —
    // live-observed as dozens of vacancies auto-published with nobody touching a
    // button. ModerationService.alreadySent is a second, independent safety net against
    // the same failure mode, but the fix belongs here: don't create the replay at all.
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
        if (channelBotToken == null || channelBotToken.isBlank()) {
            log.warn("Модерация включена, но TELEGRAM_CHANNEL_BOT_TOKEN не задан — поллер не запущен");
            return;
        }
        if (featureFlags.isSubscriptionsEnabled()) {
            // TelegramBotPoller also long-polls this exact token when subscriptions are
            // on — the two would fight over getUpdates (see class javadoc). Not a hard
            // stop (moderation still mostly works, just with intermittent missed
            // callback_query updates), but this needs to be loud, not a silent 409 loop.
            log.error("Модерация и подписки одновременно включены — оба поллера делят app.telegram.channel-bot-token " +
                "и будут конфликтовать за getUpdates (см. javadoc ModerationBotPoller). Нужен отдельный токен для одного из них.");
        }
        offset = loadOffset();
        running = true;
        Thread.ofVirtual().name("moderation-bot-poller").start(this::pollLoop);
        log.info("Поллер модерации запущен (long-polling), offset={}", offset);
    }

    private Path offsetFile() {
        return java.nio.file.Paths.get(dataDir, "moderation-offset.txt");
    }

    /** 0 (fetch everything Telegram is still holding) on first-ever run or any read
     *  failure — never lets a corrupt/missing file crash startup. */
    private long loadOffset() {
        try {
            return Long.parseLong(Files.readString(offsetFile()).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveOffset(long value) {
        try {
            Files.writeString(offsetFile(), String.valueOf(value));
        } catch (Exception e) {
            log.warn("Не удалось сохранить offset поллера модерации: {}", e.getMessage());
        }
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
        String url = String.format(GET_UPDATES_URL, channelBotToken, offset);
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
            // Persisted BEFORE handleUpdate, not after: if the app dies mid-handling
            // (crash, OOM-kill, deploy restart racing this exact instant), we want the
            // NEXT boot to skip this update rather than replay it — a lost/never-applied
            // decision is a stuck card the owner can just re-tap; a phantom republish
            // from replaying it twice is the actual danger (see class javadoc).
            saveOffset(offset);
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

        // The tapped message's own id — needed to delete the card afterward (see
        // ModerationService) so the ✅/❌ buttons don't just sit there clickable forever.
        // Absent (rare: an old/edited message) means "nothing to clean up", not an error.
        Long messageId = null;
        if (callback.get("message") instanceof Map<?, ?> message
                && message.get("message_id") instanceof Number messageIdNum) {
            messageId = messageIdNum.longValue();
        }

        // No vacancy id — acts on every vacancy currently in the batch (see
        // ModerationService.resolveApproveAll), not one specific row.
        if ("modpuball".equals(data)) {
            moderationService.resolveApproveAll(messageId);
            return;
        }

        Long vacancyId = parseVacancyId(data);
        if (vacancyId == null) {
            log.warn("Нераспознанный callback_data модерации: {}", data);
            return;
        }
        if (data.startsWith("modpub:")) {
            moderationService.resolveApprove(vacancyId, messageId);
        } else if (data.startsWith("modrej:")) {
            moderationService.resolveReject(vacancyId, messageId);
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
