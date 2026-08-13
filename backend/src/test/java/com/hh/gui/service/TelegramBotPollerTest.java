package com.hh.gui.service;

import com.hh.gui.model.Subscription;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * statusText is the only real logic TelegramBotPoller adds beyond delegating to
 * SubscriptionService — worth locking in directly since a Telegram-API-mocked
 * end-to-end test doesn't exist anywhere in this codebase yet (none of the polling
 * loop is tested). Private method reached via reflection, same pattern as
 * VacancyPipelineServiceTest for its own private formatters.
 */
class TelegramBotPollerTest {

    private final TelegramBotPoller poller = new TelegramBotPoller(null, null, null);

    private String statusText(Subscription s) throws Exception {
        Method m = TelegramBotPoller.class.getDeclaredMethod("statusText", Subscription.class);
        m.setAccessible(true);
        return (String) m.invoke(poller, s);
    }

    private Subscription sub(String status, String expiresAt, boolean cancelRequested) {
        Subscription s = new Subscription();
        s.setStatus(status);
        s.setExpiresAt(expiresAt);
        s.setCancelRequested(cancelRequested);
        return s;
    }

    private static String inDays(int days) {
        return Instant.now().plusSeconds(days * 86400L).toString();
    }

    @Test
    void active_noCancelRequested_showsExpiryOnly() throws Exception {
        String text = statusText(sub(Subscription.STATUS_ACTIVE, inDays(10), false));
        assertTrue(text.contains("Активна до"));
        assertFalse(text.contains("Автопродление"));
    }

    @Test
    void active_cancelRequested_mentionsAutoRenewOff() throws Exception {
        String text = statusText(sub(Subscription.STATUS_ACTIVE, inDays(10), true));
        assertTrue(text.contains("Активна до"));
        assertTrue(text.contains("Автопродление отключено"));
    }

    @Test
    void pending_explainsUnconfirmedPayment() throws Exception {
        String text = statusText(sub(Subscription.STATUS_PENDING, null, false));
        assertTrue(text.contains("не подтверждена"));
    }

    @Test
    void expired_mentionsExpiryDateAndRenewCommand() throws Exception {
        String text = statusText(sub(Subscription.STATUS_EXPIRED, inDays(-5), false));
        assertTrue(text.contains("закончилась"));
        assertTrue(text.contains("/subscribe"));
    }

    @Test
    void cancelled_mentionsResubscribeCommand() throws Exception {
        String text = statusText(sub(Subscription.STATUS_CANCELLED, inDays(-5), false));
        assertTrue(text.contains("/subscribe"));
    }
}
