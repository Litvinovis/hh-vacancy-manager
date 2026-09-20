package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Живой HTTP-сервер вместо мока: проверяем ровно то, что отличает Gemini от OpenRouter —
 * форму запроса (мышление выключается своим параметром) и реакцию на отказ по региону
 * выхода. Оба поведения появились 20.09.2026 после замеров на боевом ключе: из 12
 * одинаковых запросов 5 прошли, 7 вернули 400 «User location is not supported»;
 * а с max_tokens=30 без отключения мышления приходил пустой content.
 *
 * Путь сервера содержит «generativelanguage.googleapis.com», потому что анализатор
 * узнаёт Gemini по URL — так же, как OpenRouter.
 */
class GeminiCallTest {

    private static final String GEO_400 =
        "[{\"error\":{\"code\":400,\"message\":\"User location is not supported for the API use.\","
        + "\"status\":\"FAILED_PRECONDITION\"}}]";
    private static final String OK_BODY =
        "{\"model\":\"gemini-3.1-flash-lite\",\"choices\":[{\"message\":{\"content\":\"готово\"}}],"
        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}";

    private HttpServer server;
    private VacancyAiAnalyzer analyzer;
    private SimpleMeterRegistry registry;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    /** Сколько первых запросов отклонить по региону, прежде чем ответить нормально. */
    private volatile int geoFailures;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generativelanguage.googleapis.com/v1beta/openai/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean fail = calls.incrementAndGet() <= geoFailures;
            byte[] body = (fail ? GEO_400 : OK_BODY).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(fail ? 400 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort()
            + "/generativelanguage.googleapis.com/v1beta/openai/chat/completions";
        RuntimeConfig config = new RuntimeConfig();
        config.setAiRequestDelayMs(0);
        config.setAiProviders(List.of(new AiProviderConfig("gemini", url, "k", "gemini-3.1-flash-lite")));
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
    void geminiRequest_disablesThinkingSoTheBudgetGoesToTheAnswer() {
        assertEquals("готово", analyzer.generateText("привет", 100));
        assertTrue(lastBody.get().contains("\"reasoning_effort\":\"none\""),
            "без этого модели 3.x тратят весь max_tokens на размышление: " + lastBody.get());
        assertFalse(lastBody.get().contains("\"reasoning\":"),
            "параметр OpenRouter Gemini не понимает: " + lastBody.get());
    }

    @Test
    void locationRejection_isRetriedImmediatelyOnTheSameProvider() {
        geoFailures = 3;
        assertEquals("готово", analyzer.generateText("привет", 100));
        assertEquals(4, calls.get(), "три отказа по региону и четвёртый — успешный");
    }

    @Test
    void endlessLocationRejection_eventuallyGivesUpInsteadOfLoopingForever() {
        geoFailures = Integer.MAX_VALUE;
        assertNull(analyzer.generateText("привет", 100));
        assertTrue(calls.get() <= 12, "повторы должны быть ограничены, а не бесконечны: " + calls.get());
    }

    @Test
    void modelThatActuallyAnswered_isRecordedFromTheResponse() {
        analyzer.generateText("привет", 100);

        assertEquals(1.0, registry.counter("ai_model_requests_total", "application", "hh-gui",
            "provider", "gemini", "model", "gemini-3.1-flash-lite").count(),
            "в конфиге может стоять список моделей — считать надо ту, что ответила");
    }
}
