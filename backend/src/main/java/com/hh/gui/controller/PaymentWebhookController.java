package com.hh.gui.controller;

import com.hh.gui.config.FeatureFlags;
import com.hh.gui.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Receives payment-confirmation callbacks. Excluded from session auth (see
 * WebMvcConfig) — the caller is the payment provider's servers, not a browser with a
 * Telegram-bot session, so it authenticates itself via the shared secret checked here
 * instead. No PaymentGateway implementation calls this yet (see StubPaymentGateway) —
 * this endpoint, SubscriptionService.activate()'s idempotency, and the notifications it
 * sends are built and tested ahead of the real provider so wiring one in later is
 * "implement createCheckout() and point its dashboard at this URL", not a rewrite of
 * everything downstream of "a payment was confirmed."
 *
 * Payload shape follows YooKassa's actual webhook notification (the provider this app
 * plans to integrate — see PaymentGateway's javadoc), since building against an
 * invented shape now would just mean reshaping this controller later for no reason:
 * {"event": "payment.succeeded" | "payment.canceled", "object": {"id": "...",
 * "metadata": {"telegramUserId": "..."}}}. The other fields YooKassa sends (amount,
 * status, paid) aren't read — event and metadata are all the state machine needs.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final SubscriptionService subscriptionService;
    private final FeatureFlags featureFlags;

    @Value("${app.subscriptions.webhook-secret:}")
    private String webhookSecret;

    public PaymentWebhookController(SubscriptionService subscriptionService, FeatureFlags featureFlags) {
        this.subscriptionService = subscriptionService;
        this.featureFlags = featureFlags;
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String presentedSecret,
                                      @RequestBody Map<String, Object> payload) {
        if (!featureFlags.isSubscriptionsEnabled()) {
            // Same posture as TelegramBotPoller not starting at all while the flag is
            // off: nothing about subscriptions exists yet as far as an outside caller
            // should be able to tell.
            return ResponseEntity.notFound().build();
        }
        if (!secretMatches(presentedSecret)) {
            log.warn("Webhook оплаты: неверный или отсутствующий секрет");
            return ResponseEntity.status(401).build();
        }

        Object event = payload.get("event");
        Object objectRaw = payload.get("object");
        if (!(objectRaw instanceof Map<?, ?> object)) {
            log.warn("Webhook оплаты: тело без object: {}", payload);
            return ResponseEntity.badRequest().build();
        }
        Long telegramUserId = extractTelegramUserId(object);
        if (telegramUserId == null) {
            log.warn("Webhook оплаты: нет metadata.telegramUserId: {}", payload);
            return ResponseEntity.badRequest().build();
        }
        String paymentId = object.get("id") != null ? String.valueOf(object.get("id")) : null;

        if ("payment.succeeded".equals(event)) {
            try {
                subscriptionService.activate(telegramUserId, paymentId, SubscriptionService.SUBSCRIPTION_PERIOD_DAYS);
            } catch (IllegalStateException e) {
                // No subscription row for this telegramUserId — either a forged payload
                // (secret check above already gates that) or a genuine data mismatch.
                // Either way there's nothing to activate; log it, don't 500.
                log.warn("Webhook оплаты: {}", e.getMessage());
                return ResponseEntity.status(404).build();
            }
        } else if ("payment.canceled".equals(event)) {
            subscriptionService.notifyPaymentFailed(telegramUserId);
        } else {
            log.info("Webhook оплаты: событие '{}' проигнорировано", event);
        }
        return ResponseEntity.ok().build();
    }

    private boolean secretMatches(String presented) {
        if (webhookSecret == null || webhookSecret.isBlank() || presented == null) {
            return false;
        }
        // MessageDigest.isEqual is specified to run in time independent of where the
        // inputs first differ — a plain .equals() would leak the secret one byte at a
        // time to a patient attacker via response-time measurements.
        return MessageDigest.isEqual(
            webhookSecret.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Long extractTelegramUserId(Map<?, ?> object) {
        if (!(object.get("metadata") instanceof Map<?, ?> metadata)) return null;
        Object raw = metadata.get("telegramUserId");
        if (raw == null) return null;
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
