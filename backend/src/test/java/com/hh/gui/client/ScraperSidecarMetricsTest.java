package com.hh.gui.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
    void backendRestartDoesNotPublishZeroBeforeReadingExistingSidecarCounters() {
        Map<String, Integer> health = Map.of("okCount", 1319, "failedCount", 57, "blockedCount", 3);

        var before = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var after = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new ScraperSidecarMetrics(before).accept(health);
        ScraperSidecarMetrics restartedBackend = new ScraperSidecarMetrics(after);

        assertFalse(after.scrape().contains("scraper_sidecar_loads_total"));
        assertFalse(after.scrape().contains("scraper_sidecar_blocked_total"));

        restartedBackend.accept(health);
        assertEquals(counterLines(before), counterLines(after));
    }

    @Test
    void sidecarRestartPublishesTheActualCounterReset() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ScraperSidecarMetrics metrics = new ScraperSidecarMetrics(registry);
        metrics.accept(Map.of("okCount", 1319, "failedCount", 57, "blockedCount", 3));
        assertThrows(IllegalArgumentException.class, () -> metrics.accept(Map.of("okCount", 0)));
        assertEquals(1319, registry.get("scraper_sidecar_loads_total").tag("result", "ok").functionCounter().count());

        metrics.accept(Map.of("okCount", 0, "failedCount", 0, "blockedCount", 0));
        assertEquals(0, registry.get("scraper_sidecar_loads_total").tag("result", "ok").functionCounter().count());
        assertEquals(0, registry.get("scraper_sidecar_loads_total").tag("result", "failed").functionCounter().count());
        assertEquals(0, registry.get("scraper_sidecar_blocked_total").functionCounter().count());
    }

    private static List<String> counterLines(PrometheusMeterRegistry registry) {
        return registry.scrape().lines()
            .filter(line -> line.startsWith("scraper_sidecar_loads_total{") || line.startsWith("scraper_sidecar_blocked_total{"))
            .sorted().toList();
    }

    @Test
    void failureCountersExistAsZerosBeforeFirstFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ScraperMetrics(registry);
        assertEquals(0, registry.get("scraper_failures_total").tag("operation", "scrape").tag("reason", "blocked").counter().count(),
            "панель ошибок скрейпера не должна быть пустой до первой ошибки");
    }
}
