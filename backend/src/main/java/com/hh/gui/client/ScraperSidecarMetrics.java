package com.hh.gui.client;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.Map;

/**
 * Счётчики самого сайдкара hh-scraper — из его GET /health — в Prometheus.
 *
 * Зачем отдельно от scraper_failures_total: тот считает только то, что видит бэкенд, и
 * только ошибки. Сайдкар же знает и успешные загрузки, и глубину своей очереди, и сколько
 * раз его остановил DDoS-Guard (включая челлендж с кодом 200). Без этого блокировка при
 * живом процессе была видна лишь по пустым шагам пайплайна (23.09.2026).
 *
 * Счётчики сайдкара живут в его памяти и обнуляются при рестарте — поэтому они отдаются
 * как counter: Prometheus-овский increase() сброс сам распознаёт.
 */
@Component
public class ScraperSidecarMetrics {

    private static final Logger log = LoggerFactory.getLogger(ScraperSidecarMetrics.class);

    @Value("${app.scraper.url:http://127.0.0.1:8095}")
    private String scraperBaseUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Map<?, ?> last = Map.of();
    private volatile boolean up;

    public ScraperSidecarMetrics(MeterRegistry registry) {
        FunctionCounter.builder("scraper_sidecar_loads_total", this, m -> m.number("okCount"))
            .tag("application", "hh-gui").tag("result", "ok")
            .description("Успешные загрузки страниц hh.ru сайдкаром с его старта").register(registry);
        FunctionCounter.builder("scraper_sidecar_loads_total", this, m -> m.number("failedCount"))
            .tag("application", "hh-gui").tag("result", "failed")
            .description("Неудачные загрузки страниц hh.ru сайдкаром с его старта").register(registry);
        FunctionCounter.builder("scraper_sidecar_blocked_total", this, m -> m.number("blockedCount"))
            .tag("application", "hh-gui")
            .description("Загрузки, остановленные DDoS-Guard, с старта сайдкара").register(registry);
        Gauge.builder("scraper_sidecar_queue_depth", this, m -> m.number("queueDepth"))
            .tag("application", "hh-gui").description("Запросов в очереди сайдкара").register(registry);
        Gauge.builder("scraper_sidecar_up", this, m -> m.up ? 1 : 0)
            .tag("application", "hh-gui").description("1 — /health сайдкара отвечает").register(registry);
        Gauge.builder("scraper_sidecar_last_ok_age_seconds", this, ScraperSidecarMetrics::lastOkAgeSeconds)
            .tag("application", "hh-gui")
            .description("Секунд с последней успешной загрузки (с момента старта, если успешных не было)").register(registry);
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
    public void poll() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(scraperBaseUrl + "/health").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                up = false;
                return;
            }
            try (var in = conn.getInputStream()) {
                last = mapper.readValue(in, Map.class);
            }
            up = true;
        } catch (Exception e) {
            if (up) log.warn("Сайдкар скрейпа не ответил на /health: {}", e.getMessage());
            up = false;
        }
    }

    private double number(String key) {
        return last.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private double lastOkAgeSeconds() {
        Object ts = last.get("lastOkAt") != null ? last.get("lastOkAt") : last.get("startedAt");
        if (!(ts instanceof String s)) return Double.NaN;
        try {
            return Math.max(0, Instant.now().getEpochSecond() - Instant.parse(s).getEpochSecond());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Для тестов: подставить ответ /health без HTTP. */
    void accept(Map<?, ?> health) {
        this.last = health;
        this.up = true;
    }
}
