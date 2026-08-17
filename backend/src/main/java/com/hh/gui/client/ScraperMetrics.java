package com.hh.gui.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the headless-browser scraper sidecar (see ScraperClient) —
 * previously every scrape/search failure (hh.ru rate-limiting, sidecar timeouts,
 * dead sessions) was only visible in logs, invisible to Grafana/alerting.
 */
@Component
public class ScraperMetrics {

    private final MeterRegistry registry;

    public ScraperMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param operation "scrape" (single vacancy, /scrape) or "search" (hh.ru search-results
     *                  page, /search) — the two ScraperClient entry points, each with its
     *                  own volume and failure profile.
     * @param reason    normalized to a bounded set by the caller (see ScraperClient.normalizeReason) —
     *                  the sidecar's raw reason string can otherwise embed unbounded free text
     *                  (a network exception message), which would blow up cardinality if used
     *                  as a label as-is.
     */
    public void recordFailure(String operation, String reason) {
        registry.counter("scraper_failures_total", "application", "hh-gui",
            "operation", operation, "reason", reason).increment();
    }
}
