package com.hh.gui.client;

import com.hh.gui.config.RuntimeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A real local HttpServer, not a mock — same reasoning as TelegramNotifierTest: what's
 * worth verifying is that a real sidecar failure response actually reaches ScraperMetrics
 * with a bounded-cardinality reason, not that some interface method gets called.
 */
class ScraperClientTest {

    private HttpServer server;
    private ScraperClient client;
    private final List<String> recordedFailures = new ArrayList<>();

    private class RecordingMetrics extends ScraperMetrics {
        RecordingMetrics() { super(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
        @Override
        public void recordFailure(String operation, String reason) {
            recordedFailures.add(operation + ":" + reason);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scrape", ex -> {
            byte[] body = "{\"ok\":false,\"reason\":\"http_403\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/search", ex -> {
            byte[] body = "not json at all".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        client = new ScraperClient(new RuntimeConfig(), new RecordingMetrics());
        ReflectionTestUtils.setField(client, "scraperBaseUrl", "http://127.0.0.1:" + port);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void scrape_sidecarReportsFailure_recordsNormalizedReason() {
        client.scrape("123");
        assertEquals(List.of("scrape:http_403"), recordedFailures);
    }

    @Test
    void searchByUrl_malformedJsonFromSidecar_recordsClientError() {
        // Hits the catch(Exception) branch (mapper.readValue throws on "not json at
        // all"), not the "!ok" branch — a different code path, must still record.
        client.searchByUrl("https://hh.ru/search?text=x", 0);
        assertEquals(List.of("search:client_error"), recordedFailures);
    }

    private String normalizeReason(String reason) throws Exception {
        Method m = ScraperClient.class.getDeclaredMethod("normalizeReason", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, reason);
    }

    @Test
    void normalizeReason_shortSidecarReason_keptAsIs() throws Exception {
        assertEquals("http_403", normalizeReason("http_403"));
    }

    @Test
    void normalizeReason_freeTextExceptionMessage_truncatedAtColon() throws Exception {
        // Observed live: "client_error: java.net.SocketTimeoutException: Read timed out"
        // would otherwise be an unbounded-cardinality label — one series per distinct
        // exception message.
        assertEquals("client_error", normalizeReason("client_error: Read timed out"));
    }

    @Test
    void normalizeReason_nullOrBlank_becomesUnknown() throws Exception {
        assertEquals("unknown", normalizeReason(null));
        assertEquals("unknown", normalizeReason(""));
    }
}
