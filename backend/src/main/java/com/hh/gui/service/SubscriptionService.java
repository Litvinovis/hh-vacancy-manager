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

    /** The only plan there is: 200₽/month. See PaymentWebhookController — this is the
     * daysValid it passes to activate() on a confirmed payment. */
    public static final int SUBSCRIPTION_PERIOD_DAYS = 30;

    private final SubscriptionRepository repo;
    private final PaymentGateway paymentGateway;
    private final TelegramNotifier telegramNotifier;

    public SubscriptionService(SubscriptionRepository repo, PaymentGateway paymentGateway,
                                TelegramNotifier telegramNotifier) {
        this.repo = repo;
        this.paymentGateway = paymentGateway;
        this.telegramNotifier = telegramNotifier;
    }

    /** telegram_chat_id of every subscriber whose subscription is currently active. */
    public List<Long> listActiveChatIds() {
        return repo.findActiveChatIds();
    }

    public Optional<Subscription> find(long telegramUserId) {
        return repo.findByTelegramUserId(telegramUserId);
    }

    /**
     * Handles /subscribe. Three cases, in order:
     * <ul>
     *   <li>Active and cancel_requested — this is a resume, not a new sale. No new
     *       payment needed, nothing lapsed; just clear the flag.</li>
     *   <li>Active and not cancel_requested — already paid for, nothing to do.</li>
     *   <li>Anything else (first contact, still-unpaid pending, expired, cancelled) —
     *       (re)start the checkout flow. An existing row is reset to pending rather
     *       than inserted again: telegram_user_id is UNIQUE, and reusing the row keeps
     *       its history (plan_price_rub honors what THIS person has always paid,
     *       intentionally not reset to a possibly-changed default).</li>
     * </ul>
     */
    public PaymentGateway.CheckoutResult subscribe(long telegramUserId, long telegramChatId) {
        Optional<Subscription> existing = repo.findByTelegramUserId(telegramUserId);
        if (existing.isEmpty()) {
            Subscription s = new Subscription();
            s.setTelegramUserId(telegramUserId);
            s.setTelegramChatId(telegramChatId);
            s.setStatus(Subscription.STATUS_PENDING);
            s = repo.save(s);
            return paymentGateway.createCheckout(telegramUserId, s.getPlanPriceRub());
        }

        Subscription sub = existing.get();
        if (sub.isActive()) {
            if (sub.isCancelRequested()) {
                sub.setCancelRequested(false);
                repo.update(sub);
                log.info("Отмена автопродления снята: telegram_user_id={}", telegramUserId);
                return new PaymentGateway.CheckoutResult(false, null,
                    "Автопродление снова включено. Подписка активна до " + sub.getExpiresAt() + ".");
            }
            return new PaymentGateway.CheckoutResult(false, null,
                "Подписка уже активна до " + sub.getExpiresAt() + ".");
        }

        if (!Subscription.STATUS_PENDING.equals(sub.getStatus())) {
            sub.setStatus(Subscription.STATUS_PENDING);
            sub.setCancelRequested(false);
            sub.setRenewalReminderSentAt(null);
            repo.update(sub);
        }
        return paymentGateway.createCheckout(telegramUserId, sub.getPlanPriceRub());
    }

    /** Scheduled sweep — see SubscriptionRepository.expireDue. */
    public int expireDue() {
        int moved = repo.expireDue();
        if (moved > 0) log.info("Подписки: {} истекших переведены в expired/cancelled", moved);
        return moved;
    }

    /**
     * Handles /cancel. Access is never cut early: an active subscription keeps working
     * until its already-paid expiry, this only stops it from being nudged to renew and
     * records the user's intent for expireDue to act on later. A still-unpaid pending
     * row has nothing to "run out", so that one IS cancelled immediately.
     *
     * @return human-readable outcome, safe to send directly to the user.
     */
    public String cancel(long telegramUserId) {
        Optional<Subscription> opt = repo.findByTelegramUserId(telegramUserId);
        if (opt.isEmpty()) {
            return "Подписка не оформлена. Отправьте /subscribe.";
        }
        Subscription s = opt.get();

        if (s.isActive()) {
            if (s.isCancelRequested()) {
                return "Автопродление уже отключено. Доступ сохранится до " + s.getExpiresAt() + ".";
            }
            s.setCancelRequested(true);
            repo.update(s);
            log.info("Отмена автопродления запрошена: telegram_user_id={}", telegramUserId);
            return "Автопродление отключено. Доступ сохранится до " + s.getExpiresAt() +
                " — /subscribe в любой момент до этой даты включит его обратно.";
        }

        if (Subscription.STATUS_PENDING.equals(s.getStatus())) {
            s.setStatus(Subscription.STATUS_CANCELLED);
            repo.update(s);
            log.info("Оформление подписки отменено до оплаты: telegram_user_id={}", telegramUserId);
            return "Оформление отменено.";
        }

        return "Подписка уже не активна.";
    }

    /**
     * Called once a payment provider actually confirms payment (webhook — see
     * PaymentWebhookController). A renewal made before the current period ends extends
     * from the LATER of now/current expiry, not from now — paying early must never
     * shorten what was already bought. Sends the confirmation itself so activation and
     * its notification can't drift apart.
     *
     * Idempotent on externalPaymentId: a webhook provider may redeliver the same
     * notification (network hiccup, provider-side retry policy) — without this guard a
     * redelivered "payment succeeded" for a payment already recorded would silently grant
     * a second period for the one payment, since the extend-from-current-expiry logic
     * below has no other way to tell "genuine renewal" from "duplicate delivery" apart.
     */
    public void activate(long telegramUserId, String externalPaymentId, int daysValid) {
        Subscription sub = repo.findByTelegramUserId(telegramUserId).orElseThrow(
            () -> new IllegalStateException("Нет подписки для telegram_user_id=" + telegramUserId));

        if (externalPaymentId != null && externalPaymentId.equals(sub.getExternalPaymentId())
                && Subscription.STATUS_ACTIVE.equals(sub.getStatus())) {
            log.info("Подписка: повторная доставка вебхука для payment_id={}, telegram_user_id={} — пропущено",
                externalPaymentId, telegramUserId);
            return;
        }

        Instant base = Instant.now();
        if (sub.getExpiresAt() != null) {
            try {
                Instant currentExpiry = Instant.parse(sub.getExpiresAt());
                if (currentExpiry.isAfter(base)) base = currentExpiry;
            } catch (Exception ignored) {
                // Unparsable — treat as no prior expiry, extend from now.
            }
        }

        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setCancelRequested(false);
        sub.setRenewalReminderSentAt(null);
        if (sub.getStartedAt() == null || sub.getStartedAt().isBlank()) {
            sub.setStartedAt(Instant.now().toString());
        }
        sub.setExpiresAt(base.plusSeconds(daysValid * 86400L).toString());
        sub.setExternalPaymentId(externalPaymentId);
        repo.update(sub);
        log.info("Подписка активирована: telegram_user_id={}, до {}", telegramUserId, sub.getExpiresAt());

        telegramNotifier.sendViaChannelBot(
            "✅ Подписка активирована. Доступ к ранним вакансиям до " + sub.getExpiresAt() + ".",
            String.valueOf(sub.getTelegramChatId()));
    }

    /**
     * A provider's "payment failed/canceled" notification — the subscription row is
     * already 'pending' at this point (nothing to roll back), so this is purely informing
     * the person their money didn't go through, instead of leaving them to wonder why
     * /status still says pending. No-op if the row is gone (nothing to notify about).
     */
    public void notifyPaymentFailed(long telegramUserId) {
        repo.findByTelegramUserId(telegramUserId).ifPresent(s -> telegramNotifier.sendViaChannelBot(
            "Не удалось провести оплату. Попробовать снова: /subscribe",
            String.valueOf(s.getTelegramChatId())));
    }

    // Reminder window: monthly plan, three days is enough notice without feeling like spam.
    private static final int RENEWAL_REMINDER_DAYS_BEFORE = 3;
    private static final int RENEWAL_REMINDER_BATCH = 100;

    /** Scheduled sweep — nudges subscribers whose period ends soon and who haven't cancelled. */
    public int sendDueRenewalReminders() {
        List<Subscription> due = repo.findDueRenewalReminders(RENEWAL_REMINDER_DAYS_BEFORE, RENEWAL_REMINDER_BATCH);
        for (Subscription s : due) {
            telegramNotifier.sendViaChannelBot(
                "Подписка заканчивается " + s.getExpiresAt() + ". Продлить: /subscribe",
                String.valueOf(s.getTelegramChatId()));
            repo.markRenewalReminderSent(s.getId());
        }
        if (!due.isEmpty()) log.info("Подписки: отправлено {} напоминаний о продлении", due.size());
        return due.size();
    }
}
