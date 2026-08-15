package com.hh.gui.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMetricsTest {

    @Test
    void recordCollected_incrementsPerChannelCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordCollected("freelancce");
        metrics.recordCollected("freelancce");
        metrics.recordCollected("kadrout");

        assertEquals(2.0, registry.find("telegram_collected_total").tag("channel", "freelancce").counter().count());
        assertEquals(1.0, registry.find("telegram_collected_total").tag("channel", "kadrout").counter().count());
    }

    @Test
    void recordCollected_nullChannel_doesNotThrowOrRegister() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        assertDoesNotThrow(() -> metrics.recordCollected(null));
        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void recordVerdict_yes_incrementsApproved() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordVerdict("kadrout", "yes");

        assertEquals(1.0, registry.find("telegram_approved_total").tag("channel", "kadrout").counter().count());
        assertNull(registry.find("telegram_rejected_total").tag("channel", "kadrout").counter());
        assertNull(registry.find("telegram_fraud_total").tag("channel", "kadrout").counter());
    }

    @Test
    void recordVerdict_fraud_incrementsFraud() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordVerdict("vacancysmm", "fraud");

        assertEquals(1.0, registry.find("telegram_fraud_total").tag("channel", "vacancysmm").counter().count());
    }

    @Test
    void recordVerdict_no_incrementsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordVerdict("vacancysmm", "no");

        assertEquals(1.0, registry.find("telegram_rejected_total").tag("channel", "vacancysmm").counter().count());
    }

    @Test
    void recordPublished_incrementsPerChannelCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordPublished("Udalenka7");
        metrics.recordPublished("Udalenka7");

        assertEquals(2.0, registry.find("telegram_published_total").tag("channel", "Udalenka7").counter().count());
    }
}
