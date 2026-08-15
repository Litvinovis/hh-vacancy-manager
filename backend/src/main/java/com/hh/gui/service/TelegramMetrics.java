package com.hh.gui.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Per-channel Micrometer metrics for the Telegram vacancy source (Path B of
 * VacancyPipelineService.discoverFromTelegram — posts with no first-party hh.ru
 * link, judged and published as-is rather than reusing the hh.ru pipeline).
 *
 * Scoped to Path B only: Path A posts (a Telegram post that just links to an
 * hh.ru vacancy) fall into the ordinary hh.ru pipeline and aren't distinguishable
 * from RSS-discovered ones downstream, so they're not tagged by channel here —
 * see VacancyPipelineService.extractTgChannelFromHhId, which only recognizes the
 * "tg_<channel>_<id>" hh_id format Path B rows get.
 *
 * bad_frac (fraud+rejected over collected) is intentionally not stored as its own
 * gauge — it's a simple PromQL ratio over these counters and would only be a
 * second source of truth to keep in sync.
 */
@Component
public class TelegramMetrics {

    private final MeterRegistry registry;

    public TelegramMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A new Path B candidate was saved for this channel (VacancyPipelineService.discoverFromTelegram). */
    public void recordCollected(String channel) {
        if (channel == null) return;
        registry.counter("telegram_collected_total", "application", "hh-gui", "channel", channel).increment();
    }

    /** The AI verdict just recorded for a Path B vacancy from this channel — "yes"/"no"/"fraud". */
    public void recordVerdict(String channel, String verdict) {
        if (channel == null) return;
        String name = switch (verdict) {
            case "yes" -> "telegram_approved_total";
            case "fraud" -> "telegram_fraud_total";
            default -> "telegram_rejected_total";
        };
        registry.counter(name, "application", "hh-gui", "channel", channel).increment();
    }

    /** A Path B post was actually sent to a public channel (VacancyPipelineService.publishDueQueued). */
    public void recordPublished(String channel) {
        if (channel == null) return;
        registry.counter("telegram_published_total", "application", "hh-gui", "channel", channel).increment();
    }
}
