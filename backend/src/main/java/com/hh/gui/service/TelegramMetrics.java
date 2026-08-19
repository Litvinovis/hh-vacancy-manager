package com.hh.gui.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-channel Micrometer metrics for the Telegram vacancy source (Path B of
 * VacancyDiscovery.fromTelegram — posts with no first-party hh.ru link, judged
 * and published as-is rather than reusing the hh.ru pipeline).
 *
 * Scoped to Path B only: Path A posts (a Telegram post that just links to an
 * hh.ru vacancy) fall into the ordinary hh.ru pipeline and aren't distinguishable
 * from RSS-discovered ones downstream, so they're not tagged by channel here —
 * see TelegramPostParser.channelFromHhId, which only recognizes the
 * "tg_<channel>_<id>" hh_id format Path B rows get.
 *
 * bad_frac (fraud+rejected over collected) is intentionally not stored as its own
 * gauge — it's a simple PromQL ratio over these counters and would only be a
 * second source of truth to keep in sync.
 */
@Component
public class TelegramMetrics {

    private final MeterRegistry registry;
    // Gauge values, unlike counters, need a live mutable reference for Micrometer to
    // poll — one AtomicInteger per channel (or per channel+emoji), created once and
    // updated in place on every subsequent call for the same key.
    private final Map<String, AtomicInteger> viewGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> reactionGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> subscriberGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> publishedRollingGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> collectedRollingGauges = new ConcurrentHashMap<>();

    public TelegramMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registers this channel's per-channel counters (at 0, if not already registered)
     * without incrementing anything — called once per scrape, before any candidates
     * from this channel are recorded. Without this, a channel whose entire activity in
     * a 24h window lands in a single burst on a freshly-created counter is invisible
     * to Grafana's increase(): Prometheus's increase() diffs the first and last SAMPLED
     * value inside the window, and a counter born mid-burst has no earlier zero sample
     * to diff against. Verified live: "noexperience" saved 36 candidates in one run —
     * the raw counter correctly read 36, but the 24h dashboard panel showed 0.
     */
    public void preRegisterChannel(String channel) {
        if (channel == null) return;
        registry.counter("telegram_collected_total", "application", "hh-gui", "channel", channel);
        registry.counter("telegram_approved_total", "application", "hh-gui", "channel", channel);
        registry.counter("telegram_rejected_total", "application", "hh-gui", "channel", channel);
        registry.counter("telegram_fraud_total", "application", "hh-gui", "channel", channel);
        registry.counter("telegram_published_total", "application", "hh-gui", "channel", channel);
    }

    /** A new Path B candidate was saved for this channel (VacancyDiscovery.fromTelegram). */
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

    /** A Path B post was actually sent to a public channel (ChannelPublisher.publishDueQueued). */
    public void recordPublished(String channel) {
        if (channel == null) return;
        registry.counter("telegram_published_total", "application", "hh-gui", "channel", channel).increment();
    }

    /**
     * A vacancy actually went out to a public-facing destination — queued channel post,
     * delayed channel, or subscriber broadcast — tagged by which SEARCH it came from
     * rather than which Telegram SOURCE channel it was discovered on (recordPublished's
     * tag), because hh.ru-URL-discovered vacancies have no source channel at all and
     * would otherwise vanish from "posts published" entirely (channelFromHhId returns
     * null for a plain numeric hh_id, and recordPublished no-ops on a null channel).
     * One increment per vacancy per destination it's actually sent to, not per recipient —
     * a subscriber broadcast to 50 chat_ids is still one posting, not fifty.
     */
    public void recordChannelPost(String searchName) {
        if (searchName == null) return;
        registry.counter("channel_posts_published_total", "application", "hh-gui", "search", searchName).increment();
    }

    /** Total views summed across the posts just re-scraped from this channel — called both
     *  for source channels (VacancyDiscovery.fromTelegram) and the app's own output
     *  channel (ChannelEngagementTracker.checkOwnChannels), told apart by the channel tag.
     *  A gauge, not a counter: views are a snapshot of current activity each scrape, not
     *  a running total. */
    public void recordViews(String channel, int totalViews) {
        if (channel == null) return;
        viewGauges.computeIfAbsent(channel, ch -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("telegram_channel_views_recent", value, AtomicInteger::get)
                .description("Views summed across this channel's posts as of the latest scrape")
                .tag("application", "hh-gui").tag("channel", ch).register(registry);
            return value;
        }).set(totalViews);
    }

    /** Reaction counts summed across the posts just re-scraped from this channel, tagged by emoji. */
    public void recordReactions(String channel, Map<String, Integer> reactionCounts) {
        if (channel == null || reactionCounts == null) return;
        for (var entry : reactionCounts.entrySet()) {
            String key = channel + "|" + entry.getKey();
            reactionGauges.computeIfAbsent(key, k -> {
                AtomicInteger value = new AtomicInteger();
                Gauge.builder("telegram_channel_reactions_recent", value, AtomicInteger::get)
                    .description("Reaction count summed across this channel's posts as of the latest scrape, by emoji")
                    .tag("application", "hh-gui").tag("channel", channel).tag("emoji", entry.getKey()).register(registry);
                return value;
            }).set(entry.getValue());
        }
    }

    /** Current subscriber count of a channel this app publishes to (Bot API getChatMemberCount) —
     *  a gauge polled periodically; Grafana computes day/week deltas from the retained series
     *  via delta(), so no separate snapshot table is needed on this side. */
    public void recordSubscribers(String channel, int count) {
        if (channel == null) return;
        subscriberGauges.computeIfAbsent(channel, ch -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("telegram_channel_subscribers", value, AtomicInteger::get)
                .description("Current subscriber count")
                .tag("application", "hh-gui").tag("channel", ch).register(registry);
            return value;
        }).set(count);
    }

    /**
     * Recomputed straight from the vacancies table on every scheduler tick (see
     * PipelineScheduler.refreshRollingCountGauges → VacancyRepository.countPublishedSince),
     * NOT accumulated in-process — a plain Gauge, not a Counter, so an app restart
     * (this app redeploys many times a day during active development) never loses data:
     * the next tick just recomputes the same trailing window fresh from the DB. Grafana
     * plots the gauge's own value directly, no increase()/rate() needed.
     *
     * @param countsBySearch every search_name with at least one published row in the
     *                       window, as returned by countPublishedSince — searches NOT in
     *                       this map (nothing published this window) are zeroed out below
     *                       rather than left showing a stale earlier value.
     */
    public void refreshPublishedRolling(Map<String, Integer> countsBySearch) {
        for (var entry : countsBySearch.entrySet()) {
            setPublishedRolling(entry.getKey(), entry.getValue());
        }
        for (String known : publishedRollingGauges.keySet()) {
            if (!countsBySearch.containsKey(known)) setPublishedRolling(known, 0);
        }
    }

    private void setPublishedRolling(String search, int count) {
        publishedRollingGauges.computeIfAbsent(search, s -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("vacancies_published_rolling_1h", value, AtomicInteger::get)
                .description("Vacancies published to a public destination in the trailing 1h, recomputed from the DB every refresh")
                .tag("application", "hh-gui").tag("search", s).register(registry);
            return value;
        }).set(count);
    }

    /** Same reasoning as {@link #refreshPublishedRolling} — collection volume by source. */
    public void refreshCollectedRolling(Map<String, Integer> countsBySource) {
        for (var entry : countsBySource.entrySet()) {
            setCollectedRolling(entry.getKey(), entry.getValue());
        }
        for (String known : collectedRollingGauges.keySet()) {
            if (!countsBySource.containsKey(known)) setCollectedRolling(known, 0);
        }
    }

    private void setCollectedRolling(String source, int count) {
        collectedRollingGauges.computeIfAbsent(source, s -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("vacancies_collected_rolling_1d", value, AtomicInteger::get)
                .description("Vacancies collected in the trailing 24h, by source, recomputed from the DB every refresh")
                .tag("application", "hh-gui").tag("source", s).register(registry);
            return value;
        }).set(count);
    }
}
