package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Micrometer metrics for AI provider calls.
 *
 * Every counter is looked up by name+tags on each record*() call —
 * MeterRegistry.counter(name, tags) is idempotent, same name+tags always
 * resolves to the same underlying Counter — instead of being pinned to a
 * fixed set of providers/statuses decided at startup. Providers are
 * configured through Settings and can be renamed/added/removed at runtime
 * without a restart; a hardcoded provider list here previously went stale
 * (it assumed "openrouter"/"grok" while production actually runs
 * "openrouter"/"github-models", so github-models activity was silently
 * folded into the "grok" counter) and error counters were pre-registered
 * with a fixed status="429" tag regardless of the real HTTP status passed
 * to recordError, so non-429 errors were mislabeled as rate limits too.
 *
 * Providers configured at startup are still eagerly pre-registered at 0 so
 * they show up in /actuator/prometheus immediately rather than only after
 * their first request.
 */
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    public AiMetrics(MeterRegistry registry, RuntimeConfig runtimeConfig) {
        this.registry = registry;
        for (AiProviderConfig p : runtimeConfig.getAiProviders()) {
            registerProvider(p.getName());
        }
    }

    private void registerProvider(String provider) {
        Counter.builder("ai_requests_total")
            .description("Total AI requests by provider")
            .tag("application", "hh-gui")
            .tag("provider", provider)
            .register(registry);
        Counter.builder("ai_rate_limits_total")
            .description("Total 429 rate limit hits by provider")
            .tag("application", "hh-gui")
            .tag("provider", provider)
            .register(registry);
    }

    /** Record a request attempt to a provider. */
    public void recordRequest(String provider) {
        registry.counter("ai_requests_total", "application", "hh-gui", "provider", provider).increment();
    }

    /** Record a non-2xx response, tagged with its actual HTTP status. */
    public void recordError(String provider, int statusCode) {
        registry.counter("ai_errors_total", "application", "hh-gui",
            "provider", provider, "status", String.valueOf(statusCode)).increment();
    }

    /** Record a 429 rate limit hit. */
    public void recordRateLimit(String provider) {
        registry.counter("ai_rate_limits_total", "application", "hh-gui", "provider", provider).increment();
    }

    /** Record how long a single LLM HTTP call took, end to end. */
    public void recordLatency(String provider, long millis) {
        Timer.builder("ai_request_duration_seconds")
            .description("AI HTTP call latency")
            .tag("application", "hh-gui")
            .tag("provider", provider)
            .register(registry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    /** Record prompt/completion token usage reported by the provider's response (if any — not every provider reports it). */
    public void recordTokens(String provider, String type, long count) {
        if (count <= 0) return;
        registry.counter("ai_tokens_total", "application", "hh-gui",
            "provider", provider, "type", type).increment(count);
    }

    /** Record a fallback switch between two providers — a flapping primary shows up as a high rate here. */
    public void recordProviderSwitch(String from, String to) {
        registry.counter("ai_provider_switches_total", "application", "hh-gui",
            "from", from, "to", to).increment();
    }

    /** Vacancies whose AI verdict was reused from an equivalent already-scored vacancy, skipping the LLM call entirely. */
    public void recordVacanciesDeduped(long count) {
        if (count <= 0) return;
        registry.counter("ai_vacancies_deduped_total", "application", "hh-gui").increment(count);
    }

    /** Vacancies that actually went through an LLM call. */
    public void recordVacanciesAnalyzed(long count) {
        if (count <= 0) return;
        registry.counter("ai_vacancies_analyzed_total", "application", "hh-gui").increment(count);
    }

    /** Ties a gauge to live bean state (e.g. AiProviderManager.isInCooldown()) without holding a strong reference cycle. */
    public <T> void gauge(String name, String description, T obj, ToDoubleFunction<T> valueFunction) {
        Gauge.builder(name, obj, valueFunction)
            .description(description)
            .tag("application", "hh-gui")
            .register(registry);
    }
}
