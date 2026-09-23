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

    /**
     * Причины, которые сайдкар отдаёт штатно (см. scraper/server.js). Регистрируются нулями
     * при старте: счётчик с метками появляется в /actuator/prometheus только после первой
     * ошибки, и до неё панель «Ошибки скрейпера» была пустой — неотличимо от поломки сбора.
     */
    static final java.util.List<String> KNOWN_SCRAPE_REASONS =
        java.util.List.of("http_403", "blocked", "not_found", "archived", "no_job_posting_data", "error", "client_error");
    static final java.util.List<String> KNOWN_SEARCH_REASONS = java.util.List.of("blocked", "error", "client_error");

    public ScraperMetrics(MeterRegistry registry) {
        this.registry = registry;
        KNOWN_SCRAPE_REASONS.forEach(r -> counter("scrape", r));
        KNOWN_SEARCH_REASONS.forEach(r -> counter("search", r));
    }

    private io.micrometer.core.instrument.Counter counter(String operation, String reason) {
        return registry.counter("scraper_failures_total", "application", "hh-gui",
            "operation", operation, "reason", reason);
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
        counter(operation, reason).increment();
    }
}
