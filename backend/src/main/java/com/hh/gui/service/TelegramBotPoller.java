package com.hh.gui.service;

import com.hh.gui.config.FeatureFlags;
import com.hh.gui.payment.PaymentGateway;
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
 * Receives /subscribe, /status, /cancel via Telegram's getUpdates long-polling —
 * not a webhook. This deployment's public HTTPS reachability is unconfirmed (see
 * project notes on SSH being closed on this host), so long-polling was chosen
 * deliberately: it only needs outbound internet, which the app already has (calls
 * OpenRouter/Telegram's sendMessage today), and needs no setWebhook registration step.
 *
 * Entirely inert while FeatureFlags.subscriptionsEnabled is false — see start().
 */
@Component
public class TelegramBotPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);
    private static final String GET_UPDATES_URL = "https://api.telegram.org/bot%s/getUpdates?offset=%d&timeout=30";

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    private final FeatureFlags featureFlags;
    private final SubscriptionService subscriptionService;
    private final TelegramNotifier telegramNotifier;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running;
    // In-memory only: a restart can re-deliver the last update. Command handling
    // (subscribe/status/cancel) is idempotent, so re-processing one stale update
    // is harmless — not worth persisting the offset for that.
    private volatile long offset = 0;

    public TelegramBotPoller(FeatureFlags featureFlags, SubscriptionService subscriptionService,
                              TelegramNotifier telegramNotifier) {
        this.featureFlags = featureFlags;
        this.subscriptionService = subscriptionService;
        this.telegramNotifier = telegramNotifier;
    }

    @PostConstruct
    void start() {
        if (!featureFlags.isSubscriptionsEnabled()) {
            log.info("Подписки отключены (app.subscriptions.enabled=false) — поллер Telegram-бота не запущен");
            return;
        }
        if (botToken == null || botToken.isBlank()) {
            log.warn("Подписки включены, но TELEGRAM_BOT_TOKEN не задан — поллер не запущен");
            return;
        }
        running = true;
        Thread.ofVirtual().name("telegram-bot-poller").start(this::pollLoop);
        log.info("Поллер Telegram-бота запущен (long-polling)");
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
                log.error("Ошибка опроса Telegram-бота: {}", e.getMessage(), e);
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
        // Above Telegram's own 30s long-poll timeout so a normal empty-update response
        // doesn't get cut off as a client-side read timeout.
        conn.setReadTimeout(35_000);

        int code = conn.getResponseCode();
        String body = HttpUtil.readBody(conn, code);
        if (code != 200) {
            log.warn("getUpdates вернул {}: {}", code, body);
            sleepQuietly(5000);
            return;
        }

        Map<?, ?> response = mapper.readValue(body, Map.class);
        if (!(response.get("result") instanceof List<?> updates)) return;

        for (Object updateObj : updates) {
            if (!(updateObj instanceof Map<?, ?> update)) continue;
            long updateId = ((Number) update.get("update_id")).longValue();
            offset = updateId + 1;
            try {
                handleUpdate(update);
            } catch (Exception e) {
                log.error("Не удалось обработать update {}: {}", updateId, e.getMessage(), e);
            }
        }
    }

    private void handleUpdate(Map<?, ?> update) {
        if (!(update.get("message") instanceof Map<?, ?> message)) return;
        if (!(message.get("text") instanceof String text)) return;
        if (!(message.get("chat") instanceof Map<?, ?> chat)) return;
        if (!(message.get("from") instanceof Map<?, ?> from)) return;

        long chatId = ((Number) chat.get("id")).longValue();
        long userId = ((Number) from.get("id")).longValue();
        String command = text.trim().split("\\s+")[0].toLowerCase();

        String reply = switch (command) {
            case "/start", "/subscribe" -> handleSubscribe(userId, chatId);
            case "/status" -> handleStatus(userId);
            case "/cancel" -> handleCancel(userId);
            default -> "Команды: /subscribe — оформить подписку, /status — статус, /cancel — отменить.";
        };
        telegramNotifier.send(reply, String.valueOf(chatId));
    }

    private String handleSubscribe(long userId, long chatId) {
        PaymentGateway.CheckoutResult result = subscriptionService.subscribe(userId, chatId);
        return result.available() ? "Оплата: " + result.checkoutUrl() : result.message();
    }

    private String handleStatus(long userId) {
        return subscriptionService.find(userId)
            .map(s -> s.isActive()
                ? "Подписка активна до " + s.getExpiresAt() + "."
                : "Подписка неактивна (" + s.getStatus() + ").")
            .orElse("Подписка не оформлена. Отправьте /subscribe.");
    }

    private String handleCancel(long userId) {
        subscriptionService.cancel(userId);
        return "Подписка отменена.";
    }
}
