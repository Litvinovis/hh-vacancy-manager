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

    @Test
    void recordViews_setsGaugeToLatestValue_notAccumulating() {
        // A gauge, not a counter: the second scrape's total should REPLACE the first,
        // not add to it — each scrape reports the channel's current state, not a delta.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordViews("kadrout", 100);
        assertEquals(100.0, registry.find("telegram_channel_views_recent").tag("channel", "kadrout").gauge().value());

        metrics.recordViews("kadrout", 150);
        assertEquals(150.0, registry.find("telegram_channel_views_recent").tag("channel", "kadrout").gauge().value());
    }

    @Test
    void recordReactions_taggedByEmojiIndependently() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordReactions("remotevibe", java.util.Map.of("❤", 3, "🔥", 1));

        assertEquals(3.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "remotevibe").tag("emoji", "❤").gauge().value());
        assertEquals(1.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "remotevibe").tag("emoji", "🔥").gauge().value());
    }

    @Test
    void recordReactions_nullOrEmptyMap_doesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        assertDoesNotThrow(() -> metrics.recordReactions("remotevibe", null));
        assertDoesNotThrow(() -> metrics.recordReactions("remotevibe", java.util.Map.of()));
    }

    @Test
    void recordSubscribers_setsGaugeToLatestValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelegramMetrics metrics = new TelegramMetrics(registry);

        metrics.recordSubscribers("-1004333110303", 4);
        assertEquals(4.0, registry.find("telegram_channel_subscribers").tag("channel", "-1004333110303").gauge().value());

        metrics.recordSubscribers("-1004333110303", 7);
        assertEquals(7.0, registry.find("telegram_channel_subscribers").tag("channel", "-1004333110303").gauge().value());
    }
}
