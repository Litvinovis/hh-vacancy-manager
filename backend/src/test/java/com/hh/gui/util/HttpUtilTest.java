package com.hh.gui.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the bodyless-error-response case. A real HttpURLConnection
 * against a local server is used deliberately: the bug lived in how the JDK hands back
 * a null getErrorStream(), which a mock would have papered over.
 */
class HttpUtilTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // 429 with Content-Length: 0 — exactly what a rate-limiting proxy sends, and what
        // makes getErrorStream() return null.
        server.createContext("/empty-429", ex -> {
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.createContext("/error-with-body", ex -> {
            byte[] body = "{\"error\":\"slow down\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(429, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/ok", ex -> {
            byte[] body = "{\"choices\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String read(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        int code = conn.getResponseCode();
        return HttpUtil.readBody(conn, code);
    }

    @Test
    void bodylessErrorResponse_returnsEmptyStringInsteadOfThrowing() throws Exception {
        // Регрессия: getErrorStream() здесь null, и обёртка над ним бросала NPE. Из-за
        // этого 429 без тела доезжал до analyzeWithRetry как NPE, не совпадал с "429",
        // и цепочка резервных провайдеров не включалась вовсе.
        assertEquals("", read("/empty-429"));
    }

    @Test
    void errorResponseWithBody_stillReadsIt() throws Exception {
        assertEquals("{\"error\":\"slow down\"}", read("/error-with-body"));
    }

    @Test
    void successResponse_readsBody() throws Exception {
        assertEquals("{\"choices\":[]}", read("/ok"));
    }
}
