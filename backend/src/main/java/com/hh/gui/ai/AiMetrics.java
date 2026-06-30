package com.hh.gui.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for AI provider calls.
 * Exposes counters for requests by provider and errors (429s).
 * All counters are eagerly registered at startup so they appear
 * in /actuator/prometheus even before the first API call.
 */
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    // Pre-registered counters (lazy lookup by id+tags on each record)
    private final Counter requestsOpenrouter;
    private final Counter requestsGrok;
    private final Counter errorsOpenrouter;
    private final Counter errorsGrok;
    private final Counter rateLimitOpenrouter;
    private final Counter rateLimitGrok;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Eagerly register known provider counters so they show up in Prometheus at 0
        requestsOpenrouter = Counter.builder("ai_requests_total")
                .description("Total AI requests by provider")
                .tag("application", "hh-gui")
                .tag("provider", "openrouter")
                .register(registry);
        requestsGrok = Counter.builder("ai_requests_total")
                .description("Total AI requests by provider")
                .tag("application", "hh-gui")
                .tag("provider", "grok")
                .register(registry);

        errorsOpenrouter = Counter.builder("ai_errors_total")
                .description("Total AI request errors")
                .tag("application", "hh-gui")
                .tag("provider", "openrouter")
                .tag("status", "429")
                .register(registry);
        errorsGrok = Counter.builder("ai_errors_total")
                .description("Total AI request errors")
                .tag("application", "hh-gui")
                .tag("provider", "grok")
                .tag("status", "429")
                .register(registry);

        rateLimitOpenrouter = Counter.builder("ai_rate_limits_total")
                .description("Total 429 rate limit hits")
                .tag("application", "hh-gui")
                .tag("provider", "openrouter")
                .register(registry);
        rateLimitGrok = Counter.builder("ai_rate_limits_total")
                .description("Total 429 rate limit hits")
                .tag("application", "hh-gui")
                .tag("provider", "grok")
                .register(registry);
    }

    /** Record a successful or attempted request to a provider. */
    public void recordRequest(String provider) {
        counter("openrouter".equals(provider) ? requestsOpenrouter : requestsGrok).increment();
    }

    /** Record an error (non-2xx response). */
    public void recordError(String provider, int statusCode) {
        counter("openrouter".equals(provider) ? errorsOpenrouter : errorsGrok).increment();
    }

    /** Record a 429 rate limit hit. */
    public void recordRateLimit(String provider) {
        counter("openrouter".equals(provider) ? rateLimitOpenrouter : rateLimitGrok).increment();
    }

    private static Counter counter(Counter c) {
        return c;
    }
}
