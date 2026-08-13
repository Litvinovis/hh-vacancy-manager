package com.hh.gui.service;

import com.hh.gui.model.Subscription;
import com.hh.gui.payment.PaymentGateway;
import com.hh.gui.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the real SubscriptionRepository against the test DB (see project convention:
 * Mockito's inline mock maker doesn't work on this JDK — hand-written fakes instead).
 * PaymentGateway and TelegramNotifier are hand-rolled recording fakes so tests can
 * assert what SubscriptionService asked them to do without any network I/O.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubscriptionServiceTest {

    @Autowired
    private SubscriptionRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private FakePaymentGateway gateway;
    private RecordingNotifier notifier;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM subscriptions");
        gateway = new FakePaymentGateway();
        notifier = new RecordingNotifier();
        service = new SubscriptionService(repo, gateway, notifier);
    }

    private static String inDays(int days) {
        return Instant.now().plusSeconds(days * 86400L).toString();
    }

    private static class FakePaymentGateway implements PaymentGateway {
        int calls = 0;
        int lastAmountRub;
        boolean available = true;

        @Override
        public CheckoutResult createCheckout(long telegramUserId, int amountRub) {
            calls++;
            lastAmountRub = amountRub;
            return available
                ? new CheckoutResult(true, "https://pay.example/" + telegramUserId, null)
                : new CheckoutResult(false, null, "недоступно");
        }
    }

    private static class RecordingNotifier extends TelegramNotifier {
        final List<String> sentTo = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public boolean sendViaChannelBot(String message, String targetChatId) {
            sentTo.add(targetChatId);
            messages.add(message);
            return true;
        }
    }

    // ── subscribe() ──

    @Test
    void subscribe_newUser_createsRowAndRequestsCheckout() {
        PaymentGateway.CheckoutResult result = service.subscribe(1L, 1L);

        assertTrue(result.available());
        assertEquals(1, gateway.calls);
        assertEquals(200, gateway.lastAmountRub);
        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void subscribe_alreadyActive_doesNotCallGateway() {
        activate(1L, inDays(10));

        PaymentGateway.CheckoutResult result = service.subscribe(1L, 1L);

        assertFalse(result.available());
        assertTrue(result.message().contains("уже активна"));
        assertEquals(0, gateway.calls);
    }

    @Test
    void subscribe_activeWithCancelRequested_resumesWithoutNewCheckout() {
        activate(1L, inDays(10));
        service.cancel(1L);
        assertTrue(repo.findByTelegramUserId(1L).orElseThrow().isCancelRequested());

        PaymentGateway.CheckoutResult result = service.subscribe(1L, 1L);

        assertFalse(result.available(), "возобновление не требует новой оплаты");
        assertTrue(result.message().contains("включено"));
        assertEquals(0, gateway.calls);
        assertFalse(repo.findByTelegramUserId(1L).orElseThrow().isCancelRequested());
    }

    @Test
    void subscribe_stillPending_requestsCheckoutAgain() {
        service.subscribe(1L, 1L);
        service.subscribe(1L, 1L);

        assertEquals(2, gateway.calls, "повторный /subscribe до оплаты должен переспросить ссылку");
    }

    @Test
    void subscribe_expired_resetsToPendingAndRequestsCheckout() {
        activate(1L, inDays(-1));
        repo.expireDue();
        assertEquals(Subscription.STATUS_EXPIRED, repo.findByTelegramUserId(1L).orElseThrow().getStatus());

        PaymentGateway.CheckoutResult result = service.subscribe(1L, 1L);

        assertTrue(result.available());
        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void subscribe_cancelled_resetsToPendingAndRequestsCheckout() {
        service.subscribe(1L, 1L);          // pending
        service.cancel(1L);                  // pending -> cancelled immediately
        assertEquals(Subscription.STATUS_CANCELLED, repo.findByTelegramUserId(1L).orElseThrow().getStatus());

        PaymentGateway.CheckoutResult result = service.subscribe(1L, 1L);

        assertTrue(result.available());
        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void subscribe_preservesHistoricalPrice() {
        activate(1L, inDays(-1));
        repo.update(withPrice(repo.findByTelegramUserId(1L).orElseThrow(), 150));
        repo.expireDue();

        service.subscribe(1L, 1L);

        assertEquals(150, gateway.lastAmountRub, "своя историческая цена не должна слетать при переоформлении");
    }

    private Subscription withPrice(Subscription s, int price) {
        s.setPlanPriceRub(price);
        return s;
    }

    // ── cancel() ──

    @Test
    void cancel_active_setsFlagKeepsAccessUntilExpiry() {
        activate(1L, inDays(10));

        String message = service.cancel(1L);

        Subscription s = repo.findByTelegramUserId(1L).orElseThrow();
        assertEquals(Subscription.STATUS_ACTIVE, s.getStatus(), "доступ не режется сразу");
        assertTrue(s.isCancelRequested());
        assertTrue(s.isActive(), "isActive должен оставаться true до истечения");
        assertTrue(message.contains(s.getExpiresAt()));
    }

    @Test
    void cancel_alreadyCancelRequested_isNoOpWithExplanation() {
        activate(1L, inDays(10));
        service.cancel(1L);

        String message = service.cancel(1L);

        assertTrue(message.contains("уже отключено"));
    }

    @Test
    void cancel_pendingUnpaid_cancelsImmediately() {
        service.subscribe(1L, 1L);

        service.cancel(1L);

        assertEquals(Subscription.STATUS_CANCELLED, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void cancel_noSubscription_returnsHelpfulMessage() {
        assertTrue(service.cancel(999L).contains("/subscribe"));
    }

    @Test
    void cancel_expired_returnsNotActiveMessage() {
        activate(1L, inDays(-1));
        repo.expireDue();

        assertFalse(service.cancel(1L).contains("сохранится"));
    }

    // ── activate() ──

    @Test
    void activate_fresh_setsThirtyDayExpiryFromNow() {
        service.subscribe(1L, 1L);

        service.activate(1L, "pay_1", 30);

        Subscription s = repo.findByTelegramUserId(1L).orElseThrow();
        assertEquals(Subscription.STATUS_ACTIVE, s.getStatus());
        assertTrue(Instant.parse(s.getExpiresAt()).isAfter(Instant.now().plusSeconds(29L * 86400)));
    }

    @Test
    void activate_renewalBeforeExpiry_extendsFromCurrentExpiryNotFromNow() {
        // Ключевое правило: платёж заранее не должен УКОРАЧИВАТЬ уже оплаченное.
        activate(1L, inDays(10));

        service.activate(1L, "pay_2", 30);

        Instant newExpiry = Instant.parse(repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt());
        assertTrue(newExpiry.isAfter(Instant.now().plusSeconds(39L * 86400)),
            "должно быть ~10 (остаток) + 30 (новых) дней, а не просто 30 от текущего момента");
    }

    @Test
    void activate_afterLapse_extendsFromNow() {
        activate(1L, inDays(-5));
        repo.expireDue();

        service.activate(1L, "pay_3", 30);

        Instant newExpiry = Instant.parse(repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt());
        assertTrue(newExpiry.isBefore(Instant.now().plusSeconds(31L * 86400)),
            "после лапса продление считается от сейчас, а не от давно прошедшей даты");
    }

    @Test
    void activate_clearsCancelRequestedAndReminderMark() {
        activate(1L, inDays(2));
        service.cancel(1L);
        repo.markRenewalReminderSent(repo.findByTelegramUserId(1L).orElseThrow().getId());

        service.activate(1L, "pay_4", 30);

        Subscription s = repo.findByTelegramUserId(1L).orElseThrow();
        assertFalse(s.isCancelRequested(), "новая оплата отменяет ранее выставленный флаг отмены");
        assertNull(s.getRenewalReminderSentAt(), "новый цикл — новое окно напоминаний");
    }

    @Test
    void activate_sendsConfirmationToSubscriberChat() {
        service.subscribe(42L, 777L);

        service.activate(42L, "pay_5", 30);

        assertEquals(List.of("777"), notifier.sentTo);
        assertTrue(notifier.messages.get(0).contains("активирована"));
    }

    @Test
    void activate_unknownUser_throws() {
        assertThrows(IllegalStateException.class, () -> service.activate(12345L, "pay_x", 30));
    }

    @Test
    void activate_redeliveredWebhook_doesNotExtendTwice() {
        // Провайдер вправе повторно доставить то же уведомление — без этой защиты второй
        // вызов с тем же externalPaymentId молча продлил бы ещё на daysValid поверх
        // первого, подарив второй период за один платёж.
        service.subscribe(1L, 1L);
        service.activate(1L, "pay_1", 30);
        String firstExpiry = repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt();

        service.activate(1L, "pay_1", 30);

        assertEquals(firstExpiry, repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt());
        assertEquals(1, notifier.messages.size(), "повторная доставка не должна слать второе подтверждение");
    }

    @Test
    void activate_genuineRenewal_withNewPaymentId_doesExtend() {
        // В отличие от повтора — другой payment_id обязан продлить по-настоящему.
        service.subscribe(1L, 1L);
        service.activate(1L, "pay_1", 30);
        String firstExpiry = repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt();

        service.activate(1L, "pay_2", 30);

        assertNotEquals(firstExpiry, repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt());
        assertEquals(2, notifier.messages.size());
    }

    // ── notifyPaymentFailed() ──

    @Test
    void notifyPaymentFailed_sendsMessageToChat() {
        service.subscribe(42L, 777L);

        service.notifyPaymentFailed(42L);

        assertEquals(List.of("777"), notifier.sentTo);
        assertTrue(notifier.messages.get(0).contains("Не удалось"));
    }

    @Test
    void notifyPaymentFailed_unknownUser_isNoOp() {
        assertDoesNotThrow(() -> service.notifyPaymentFailed(999L));
        assertTrue(notifier.messages.isEmpty());
    }

    // ── sendDueRenewalReminders() ──

    @Test
    void renewalReminders_sentAndMarked_notResentOnSecondRun() {
        activate(1L, inDays(2));

        int first = service.sendDueRenewalReminders();
        int second = service.sendDueRenewalReminders();

        assertEquals(1, first);
        assertEquals(0, second, "уже уведомлённый не должен получить сообщение повторно");
        assertEquals(1, notifier.messages.size());
    }

    @Test
    void renewalReminders_skipCancelRequested() {
        activate(1L, inDays(2));
        service.cancel(1L);

        assertEquals(0, service.sendDueRenewalReminders());
    }

    // ── helper ──

    private void activate(long userId, String expiresAt) {
        Subscription s = new Subscription();
        s.setTelegramUserId(userId);
        s.setTelegramChatId(userId);
        s.setStatus(Subscription.STATUS_ACTIVE);
        s.setExpiresAt(expiresAt);
        s.setStartedAt(Instant.now().toString());
        repo.save(s);
    }
}
