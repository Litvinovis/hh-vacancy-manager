package com.hh.gui.client;

import com.hh.gui.config.RuntimeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real local HttpServer, same reasoning as ScraperClientTest: what matters is that
 * repeated real 5xx responses actually trip the circuit breaker and stop further HTTP
 * calls from going out, not that some interface method gets invoked.
 */
class HhApiClientTest {

    private HttpServer server;
    private HhApiClient client;
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private volatile int responseCode = 200;
    private volatile String responseBody = "<rss><channel></channel></rss>";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search/vacancy/rss", ex -> {
            hitCount.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(responseCode, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        client = new HhApiClient(new RuntimeConfig());
        ReflectionTestUtils.setField(client, "rssBase", "http://127.0.0.1:" + port + "/search/vacancy/rss");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private AtomicInteger consecutive5xx() {
        return (AtomicInteger) ReflectionTestUtils.getField(client, "consecutive5xx");
    }

    private Instant circuitOpenedAt() {
        return (Instant) ReflectionTestUtils.getField(client, "circuitOpenedAt");
    }

    @Test
    void fetchRss_persistentServerErrors_opensCircuitAndSkipsSubsequentCalls() {
        responseCode = 503;

        client.fetchRss("java", 1, null, 0); // 2 attempts internally: consecutive5xx=2, still closed
        client.fetchRss("java", 1, null, 0); // 3rd hit crosses threshold: circuit opens

        assertNotNull(circuitOpenedAt(), "цепь должна разомкнуться после порога подряд идущих 5xx");
        int hitsBeforeSkip = hitCount.get();
        assertTrue(hitsBeforeSkip >= 3, "должно быть минимум 3 реальных обращения к серверу");

        client.fetchRss("java", 1, null, 0);

        assertEquals(hitsBeforeSkip, hitCount.get(), "при разомкнутой цепи новых HTTP-обращений быть не должно");
    }

    @Test
    void fetchRss_successAfterFailures_resetsConsecutiveFailureCount() {
        responseCode = 503;
        client.fetchRss("java", 1, null, 0); // consecutive5xx=2 (не достигнут порог 3)
        assertEquals(2, consecutive5xx().get());

        responseCode = 200;
        client.fetchRss("java", 1, null, 0); // успех — счётчик должен сброситься

        assertEquals(0, consecutive5xx().get());
        assertNull(circuitOpenedAt());
    }

    @Test
    void fetchRss_non5xxError_doesNotAffectCircuitBreaker() {
        responseCode = 403; // DDoS-Guard style block — не признак деградации сервера
        client.fetchRss("java", 1, null, 0);
        client.fetchRss("java", 1, null, 0);
        client.fetchRss("java", 1, null, 0);

        assertEquals(0, consecutive5xx().get(), "403 не должен считаться как 5xx для цепи");
        assertNull(circuitOpenedAt());
    }
}
