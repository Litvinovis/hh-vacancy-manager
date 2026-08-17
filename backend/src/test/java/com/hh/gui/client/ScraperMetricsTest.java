package com.hh.gui.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScraperMetricsTest {

    @Test
    void recordFailure_taggedByOperationAndReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScraperMetrics metrics = new ScraperMetrics(registry);

        metrics.recordFailure("scrape", "http_403");
        metrics.recordFailure("scrape", "http_403");
        metrics.recordFailure("search", "http_403");

        assertEquals(2.0, registry.find("scraper_failures_total")
            .tag("operation", "scrape").tag("reason", "http_403").counter().count());
        assertEquals(1.0, registry.find("scraper_failures_total")
            .tag("operation", "search").tag("reason", "http_403").counter().count());
    }
}
