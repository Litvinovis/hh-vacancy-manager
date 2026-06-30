package com.hh.gui.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Micrometer metrics for AI provider calls.
 * Exposes counters for requests by provider and errors (429s).
 */
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    // Counters
    private final Counter.Builder requestsBuilder;
    private final Counter.Builder errorsBuilder;
    private final Counter.Builder rateLimitBuilder;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestsBuilder = Counter.builder("ai_requests_total")
                .description("Total AI requests by provider")
                .tag("application", "hh-gui");
        this.errorsBuilder = Counter.builder("ai_errors_total")
                .description("Total AI request errors")
                .tag("application", "hh-gui");
        this.rateLimitBuilder = Counter.builder("ai_rate_limits_total")
                .description("Total 429 rate limit hits")
                .tag("application", "hh-gui");
    }

    /** Record a successful or attempted request to a provider. */
    public void recordRequest(String provider) {
        requestsBuilder
                .tag("provider", provider)
                .register(registry)
                .increment();
    }

    /** Record an error (non-2xx response). */
    public void recordError(String provider, int statusCode) {
        errorsBuilder
                .tag("provider", provider)
                .tag("status", String.valueOf(statusCode))
                .register(registry)
                .increment();
    }

    /** Record a 429 rate limit hit. */
    public void recordRateLimit(String provider) {
        rateLimitBuilder
                .tag("provider", provider)
                .register(registry)
                .increment();
    }
}
