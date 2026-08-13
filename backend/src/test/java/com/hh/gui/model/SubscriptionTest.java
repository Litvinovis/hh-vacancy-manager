package com.hh.gui.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Регрессия по монетизации: isActive() проверял только статус, поэтому подписка,
 * once activated, оставалась активной навсегда — ничто не снимало этот статус,
 * рассылка продолжала уходить, а subscribe() отказывался открыть новую оплату,
 * считая подписку действующей. Один платёж давал доступ бессрочно.
 */
class SubscriptionTest {

    private static Subscription withStatusAndExpiry(String status, String expiresAt) {
        Subscription s = new Subscription();
        s.setStatus(status);
        s.setExpiresAt(expiresAt);
        return s;
    }

    private static String inDays(int days) {
        return Instant.now().plusSeconds(days * 86400L).toString();
    }

    @Test
    void activeWithFutureExpiry_isActive() {
        Subscription s = withStatusAndExpiry(Subscription.STATUS_ACTIVE, inDays(10));
        assertTrue(s.isActive());
        assertFalse(s.isExpired());
    }

    @Test
    void activeWithPastExpiry_isNotActive() {
        Subscription s = withStatusAndExpiry(Subscription.STATUS_ACTIVE, inDays(-1));
        assertTrue(s.isExpired());
        assertFalse(s.isActive(), "оплаченный период кончился — доступ должен закрыться");
    }

    @Test
    void activeWithoutExpiry_isNotActive() {
        // activate() всегда проставляет дату, поэтому её отсутствие означает, что мы не
        // можем подтвердить оплату. Доступ к платному фиду в таком случае не выдаём.
        assertFalse(withStatusAndExpiry(Subscription.STATUS_ACTIVE, null).isActive());
        assertFalse(withStatusAndExpiry(Subscription.STATUS_ACTIVE, "").isActive());
    }

    @Test
    void activeWithUnparsableExpiry_isNotActive() {
        assertFalse(withStatusAndExpiry(Subscription.STATUS_ACTIVE, "не-дата").isActive());
    }

    @Test
    void nonActiveStatuses_areNeverActive() {
        assertFalse(withStatusAndExpiry(Subscription.STATUS_PENDING, inDays(10)).isActive());
        assertFalse(withStatusAndExpiry(Subscription.STATUS_CANCELLED, inDays(10)).isActive());
        assertFalse(withStatusAndExpiry(Subscription.STATUS_EXPIRED, inDays(10)).isActive());
    }
}
