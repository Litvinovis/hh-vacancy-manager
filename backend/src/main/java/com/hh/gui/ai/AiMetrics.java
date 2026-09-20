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
 * Every counter is looked up by name+tags on each record*() call
 * (MeterRegistry.counter is idempotent — same name+tags always resolves to
 * the same Counter) instead of being pinned to a fixed provider/status set at
 * startup: providers are renamed/added/removed at runtime via Settings, and a
 * hardcoded list previously went stale and silently mislabeled activity under
 * the wrong provider name. Providers configured at startup are still eagerly
 * pre-registered at 0 so they show up in /actuator/prometheus immediately
 * rather than only after their first request.
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

    /**
     * Исход AI-оценки по вердикту (yes/no/fraud) — раньше по метрикам было видно только
     * «сколько проанализировано», без разбивки, и доля отсева считалась запросами в БД.
     */
    public void recordVerdict(String verdict) {
        registry.counter("vacancies_verdict_total", "application", "hh-gui", "verdict", verdict).increment();
    }

    /**
     * Кандидаты, отсеянные дешёвым прескрином карточек — до скрейпа и полного анализа.
     * Отдельно от verdict: это другой этап воронки и другая цена ошибки (здесь решение
     * принимается по одному заголовку).
     */
    public void recordPrescreenRejected(long count) {
        registry.counter("vacancies_prescreen_rejected_total", "application", "hh-gui").increment(count);
    }

    /** Вакансии, отброшенные перед отправкой: дедуп, порог канала, фильтр качества. */
    public void recordDropped(String reason, long count) {
        registry.counter("vacancies_dropped_total", "application", "hh-gui", "reason", reason).increment(count);
    }

    /** Пост ушёл на стену VK (см. VkPublishQueue). */
    public void recordVkPost() {
        registry.counter("vk_posts_published_total", "application", "hh-gui").increment();
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

    /**
     * Record a failed analysis attempt that exhausted its retry budget for one provider —
     * covers everything recordError doesn't: an HTTP 200 with a malformed/incomplete
     * response body (LlmException.Kind.BAD_RESPONSE — no "choices", empty content, no
     * JSON array found) has no non-2xx status to tag, so it was previously only visible
     * in logs, never in a metric. kind is LlmException.Kind.name() (BAD_RESPONSE/
     * RATE_LIMIT/AUTH/TRANSPORT); stage tells apart the full-analysis prompt from the
     * cheaper card prescreen, since they fail independently and at very different rates.
     */
    public void recordAnalysisFailure(String provider, String kind, String stage) {
        registry.counter("ai_analysis_failures_total", "application", "hh-gui",
            "provider", provider, "kind", kind, "stage", stage).increment();
    }

    /** Record a 429 rate limit hit. */
    /**
     * 403 от щита перед API (Cloudflare и подобные) — считается отдельно от ошибок API:
     * по ai_errors_total такую блокировку не отличить от неверного ключа, а реакция на них
     * противоположная (подождать против «чинить доступ»). См. LlmException.Kind.EDGE_BLOCKED.
     */
    public void recordEdgeBlock(String provider) {
        registry.counter("ai_edge_blocked_total", "provider", provider,
            "application", "hh-gui", "service", "ai-analyzer").increment();
    }

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

    /** New vacancies saved by a discovery pass, tagged by where they came from
     *  (e.g. "hh.ru") — the Telegram side of this already has its own per-channel
     *  counter (TelegramMetrics.recordCollected); this is the equivalent for
     *  everything else, so a Grafana panel can show total volume by source. */
    public void recordVacanciesCollected(String source, long count) {
        if (count <= 0) return;
        registry.counter("vacancies_collected_total", "application", "hh-gui", "source", source).increment(count);
    }

    /** Ties a gauge to live bean state (e.g. AiProviderManager.isInCooldown()) without holding a strong reference cycle. */
    public <T> void gauge(String name, String description, T obj, ToDoubleFunction<T> valueFunction) {
        Gauge.builder(name, obj, valueFunction)
            .description(description)
            .tag("application", "hh-gui")
            .register(registry);
    }

    /**
     * Счётчики воронки и сбоев — нулями, при старте. Зачем: см. MetricsPreRegistrar.
     * Здесь только метки с известным набором значений: вердикты, причины отбраковки,
     * виды ошибок LLM. Статусы HTTP и причины сбоев скрейпера приходят из данных и
     * не перечисляются — выдуманная серия хуже честно отсутствующей.
     */
    public void preRegisterCounters() {
        registry.counter("ai_vacancies_analyzed_total", "application", "hh-gui");
        registry.counter("ai_vacancies_deduped_total", "application", "hh-gui");
        registry.counter("vacancies_prescreen_rejected_total", "application", "hh-gui");
        registry.counter("vk_posts_published_total", "application", "hh-gui");
        for (String verdict : new String[]{"yes", "no", "fraud"}) {
            registry.counter("vacancies_verdict_total", "application", "hh-gui", "verdict", verdict);
        }
        for (String reason : new String[]{"similarity", "channel_floor", "quality"}) {
            registry.counter("vacancies_dropped_total", "application", "hh-gui", "reason", reason);
        }
        for (LlmException.Kind kind : LlmException.Kind.values()) {
            for (String stage : new String[]{"analyze", "prescreen"}) {
                registry.counter("ai_analysis_failures_total", "application", "hh-gui",
                    "kind", kind.name(), "stage", stage);
            }
        }
    }
}
