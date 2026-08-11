package com.hh.gui.service;

import com.hh.gui.model.Subscription;
import com.hh.gui.payment.PaymentGateway;
import com.hh.gui.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Paid early-access subscribers of the Telegram bot. Talks to PaymentGateway to
 * start a checkout, but never marks a subscription active itself on that basis —
 * see StubPaymentGateway's javadoc, no provider yet actually confirms a payment.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository repo;
    private final PaymentGateway paymentGateway;

    public SubscriptionService(SubscriptionRepository repo, PaymentGateway paymentGateway) {
        this.repo = repo;
        this.paymentGateway = paymentGateway;
    }

    /** telegram_chat_id of every subscriber whose subscription is currently active. */
    public List<Long> listActiveChatIds() {
        return repo.findActiveChatIds();
    }

    public Optional<Subscription> find(long telegramUserId) {
        return repo.findByTelegramUserId(telegramUserId);
    }

    /**
     * Handles /subscribe: creates a pending row on first contact (idempotent — repeated
     * /subscribe just re-requests checkout), then asks PaymentGateway to start a checkout.
     */
    public PaymentGateway.CheckoutResult subscribe(long telegramUserId, long telegramChatId) {
        Subscription sub = repo.findByTelegramUserId(telegramUserId).orElseGet(() -> {
            Subscription s = new Subscription();
            s.setTelegramUserId(telegramUserId);
            s.setTelegramChatId(telegramChatId);
            s.setStatus(Subscription.STATUS_PENDING);
            return repo.save(s);
        });
        if (sub.isActive()) {
            return new PaymentGateway.CheckoutResult(false, null,
                "Подписка уже активна до " + sub.getExpiresAt() + ".");
        }
        return paymentGateway.createCheckout(telegramUserId, sub.getPlanPriceRub());
    }

    public void cancel(long telegramUserId) {
        repo.findByTelegramUserId(telegramUserId).ifPresent(s -> {
            s.setStatus(Subscription.STATUS_CANCELLED);
            repo.update(s);
            log.info("Подписка отменена: telegram_user_id={}", telegramUserId);
        });
    }

    /**
     * Called once a payment provider actually confirms payment (not built yet — see
     * PaymentGateway's javadoc). Kept here, unused for now, so the activation step
     * that a real provider's webhook will call already exists and is tested against.
     */
    public void activate(long telegramUserId, String externalPaymentId, int daysValid) {
        Subscription sub = repo.findByTelegramUserId(telegramUserId).orElseThrow(
            () -> new IllegalStateException("Нет подписки для telegram_user_id=" + telegramUserId));
        String now = Instant.now().toString();
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setStartedAt(now);
        sub.setExpiresAt(Instant.now().plusSeconds(daysValid * 86400L).toString());
        sub.setExternalPaymentId(externalPaymentId);
        repo.update(sub);
        log.info("Подписка активирована: telegram_user_id={}, до {}", telegramUserId, sub.getExpiresAt());
    }
}
