package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for two real production bugs found while investigating why the
 * Grafana AI panels looked uninformative:
 *
 * 1. recordError() used to increment a counter pre-registered with a hardcoded
 *    status="429" tag regardless of the real HTTP status passed in, so every non-429
 *    error (500s, timeouts reported as an error status, etc.) was silently mislabeled
 *    as a rate limit in Prometheus — "AI Errors by Status" could never show anything
 *    but 429.
 * 2. Provider counters were pre-registered for a hardcoded "openrouter"/"grok" pair
 *    that didn't match production (which actually runs "openrouter"/"github-models"),
 *    and recordRequest/recordError/recordRateLimit used a binary
 *    "openrouter".equals(provider) ternary — so every github-models data point was
 *    silently counted as "grok" instead.
 */
class AiMetricsTest {

    @Test
    void recordError_taggedWithRealStatusCode_not429() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, new RuntimeConfig());

        metrics.recordError("openrouter", 500);

        assertNull(registry.find("ai_errors_total").tag("provider", "openrouter").tag("status", "429").counter(),
            "a 500 must not be counted under status=429");
        var counter500 = registry.find("ai_errors_total").tag("provider", "openrouter").tag("status", "500").counter();
        assertNotNull(counter500, "a 500 must be counted under its own real status tag");
        assertEquals(1.0, counter500.count());
    }

    @Test
    void recordError_differentStatusCodes_trackedSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, new RuntimeConfig());

        metrics.recordError("openrouter", 500);
        metrics.recordError("openrouter", 502);
        metrics.recordError("openrouter", 500);

        assertEquals(2.0, registry.find("ai_errors_total").tag("provider", "openrouter").tag("status", "500").counter().count());
        assertEquals(1.0, registry.find("ai_errors_total").tag("provider", "openrouter").tag("status", "502").counter().count());
    }

    @Test
    void recordRequest_forConfiguredNonStandardProvider_notFoldedIntoAnotherProvider() {
        // "github-models" is a real production provider name — must not be miscounted
        // as "grok" the way a hardcoded two-provider ternary used to.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, providersConfig("openrouter", "github-models"));

        metrics.recordRequest("github-models");

        assertEquals(1.0, registry.find("ai_requests_total").tag("provider", "github-models").counter().count());
        var grokCounter = registry.find("ai_requests_total").tag("provider", "grok").counter();
        assertNull(grokCounter, "a provider not in config must never receive another provider's data");
    }

    @Test
    void providersFromConfig_areEagerlyPreRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AiMetrics(registry, providersConfig("openrouter", "github-models"));

        assertNotNull(registry.find("ai_requests_total").tag("provider", "openrouter").counter());
        assertNotNull(registry.find("ai_requests_total").tag("provider", "github-models").counter());
        assertEquals(0.0, registry.find("ai_requests_total").tag("provider", "openrouter").counter().count());
    }

    @Test
    void recordTokens_zeroOrNegative_notRecorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, new RuntimeConfig());

        metrics.recordTokens("openrouter", "prompt", 0);
        metrics.recordTokens("openrouter", "prompt", -5);

        assertNull(registry.find("ai_tokens_total").tag("provider", "openrouter").counter());
    }

    @Test
    void recordTokens_positiveCount_accumulates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, new RuntimeConfig());

        metrics.recordTokens("openrouter", "prompt", 120);
        metrics.recordTokens("openrouter", "prompt", 30);

        assertEquals(150.0, registry.find("ai_tokens_total").tag("provider", "openrouter").tag("type", "prompt").counter().count());
    }

    private RuntimeConfig providersConfig(String... names) {
        RuntimeConfig config = new RuntimeConfig();
        config.setAiProviders(List.of(names).stream()
            .map(n -> new AiProviderConfig(n, "http://localhost/" + n, "key", "model"))
            .toList());
        return config;
    }
}
