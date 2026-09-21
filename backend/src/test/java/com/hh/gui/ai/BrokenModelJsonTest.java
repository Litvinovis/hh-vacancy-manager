package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Модель ответила 200 и валидным конвертом, но сам массив вердиктов внутри content
 * испорчен (живой случай 21.09.2026: OpenRouter трижды подряд вернул «Unexpected close
 * marker ']'»). Это ошибка ответа, а не сети — в логе и метрике она должна значиться
 * как BAD_RESPONSE, иначе на дашборде «сбои по видам» это выглядит как проблемы с сетью.
 */
class BrokenModelJsonTest {

    private static final String BROKEN_ARRAY_BODY =
        "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"[{\\\"id\\\":\\\"1\\\",\\\"verdict\\\":\\\"yes\\\"]\"}}]}";

    private HttpServer server;
    private VacancyAiAnalyzer analyzer;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = BROKEN_ARRAY_BODY.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        RuntimeConfig config = new RuntimeConfig();
        config.setAiRequestDelayMs(0);
        config.setMaxRetries(1);   // одна попытка, чтобы не ждать backoff
        config.setAiProviders(List.of(new AiProviderConfig("openrouter",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "k", "m")));
        registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry, config);
        analyzer = new VacancyAiAnalyzer(config, new AiProviderManager(config, metrics), metrics,
            new com.hh.gui.client.CurrencyRateService());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void brokenVerdictArray_isCountedAsBadResponseNotTransport() {
        Vacancy v = new Vacancy();
        v.setHhId("1");
        v.setTitle("Оператор чата");
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Общая удалёнка";
        job.kind = com.hh.gui.model.SearchKind.EDITORIAL;

        assertTrue(analyzer.analyzeBatch(List.of(v), job).isEmpty());

        assertEquals(1.0, failures("BAD_RESPONSE"), "испорченный массив — это ответ модели");
        assertEquals(0.0, failures("TRANSPORT"), "сеть тут ни при чём");
    }

    private double failures(String kind) {
        return registry.counter("ai_analysis_failures_total", "application", "hh-gui",
            "provider", "openrouter", "kind", kind, "stage", "analyze").count();
    }
}
