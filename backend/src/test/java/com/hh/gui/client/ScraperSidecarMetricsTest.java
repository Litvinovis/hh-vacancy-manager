package com.hh.gui.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScraperSidecarMetricsTest {

    @Test
    void exportsSidecarHealthCountersAsPrometheusMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScraperSidecarMetrics metrics = new ScraperSidecarMetrics(registry);
        metrics.accept(Map.of("ok", true, "okCount", 12, "failedCount", 3, "blockedCount", 2, "queueDepth", 4,
            "lastOkAt", Instant.now().minusSeconds(600).toString()));

        assertEquals(12, registry.get("scraper_sidecar_loads_total").tag("result", "ok").functionCounter().count());
        assertEquals(3, registry.get("scraper_sidecar_loads_total").tag("result", "failed").functionCounter().count());
        assertEquals(2, registry.get("scraper_sidecar_blocked_total").functionCounter().count());
        assertEquals(4, registry.get("scraper_sidecar_queue_depth").gauge().value());
        assertEquals(1, registry.get("scraper_sidecar_up").gauge().value());
        double age = registry.get("scraper_sidecar_last_ok_age_seconds").gauge().value();
        assertTrue(age >= 600 && age < 660, "age=" + age);
    }

    @Test
    void failureCountersExistAsZerosBeforeFirstFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ScraperMetrics(registry);
        assertEquals(0, registry.get("scraper_failures_total").tag("operation", "scrape").tag("reason", "blocked").counter().count(),
            "панель ошибок скрейпера не должна быть пустой до первой ошибки");
    }
}
