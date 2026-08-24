package com.hh.gui.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real local HttpServer, not a mock — same reasoning as TelegramNotifierTest: this
 * class's whole job is talking HTTP to the VK API, so what's worth verifying is the
 * actual request it sends and how it tells a VK-level error apart from success, both of
 * which arrive as HTTP 200 (see VkNotifier.hasError).
 */
class VkNotifierTest {

    private HttpServer server;
    private int port;
    private VkNotifier notifier;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/method/wall.post", ex -> {
            lastRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String requestBody = lastRequestBody.get();
            if (requestBody.contains("access_token=SERVERERRORTOKEN")) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            byte[] body;
            if (requestBody.contains("access_token=BADTOKEN")) {
                body = "{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}".getBytes(StandardCharsets.UTF_8);
            } else if (requestBody.contains("access_token=NOWALLACCESS")) {
                body = "{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied\"}}".getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"response\":{\"post_id\":123}}".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();

        notifier = new VkNotifier();
        ReflectionTestUtils.setField(notifier, "apiBaseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(notifier, "accessToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "groupId", "123456789");
        ReflectionTestUtils.setField(notifier, "apiVersion", "5.199");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void post_success_returnsTrue() {
        assertTrue(notifier.post("hello"));
    }

    @Test
    void post_negatesGroupIdForOwnerIdAndPostsAsCommunity() {
        notifier.post("hello");

        assertTrue(lastRequestBody.get().contains("owner_id=-123456789"),
            "wall.post's owner_id must be the NEGATED community id, not the raw configured one");
        assertTrue(lastRequestBody.get().contains("from_group=1"),
            "must post as the community itself, not whichever admin owns the token");
    }

    @Test
    void post_urlEncodesCyrillicMessage() {
        notifier.post("привет мир");

        assertFalse(lastRequestBody.get().contains("привет"), "кириллица должна быть URL-encoded, не сырой");
    }

    @Test
    void post_vkApiErrorObjectInHttp200Body_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "accessToken", "NOWALLACCESS");

        assertFalse(notifier.post("hello"), "VK отвечает 200 даже на ошибку API — тело нужно парсить в любом случае");
    }

    @Test
    void post_badToken_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "accessToken", "BADTOKEN");

        assertFalse(notifier.post("hello"));
    }

    @Test
    void post_httpErrorStatus_returnsFalse() {
        // A different failure shape than the JSON-error-object case above — a real
        // HTTP-level failure (5xx), no VK-shaped body to parse at all.
        ReflectionTestUtils.setField(notifier, "accessToken", "SERVERERRORTOKEN");

        assertFalse(notifier.post("hello"));
    }

    @Test
    void post_missingAccessToken_returnsFalseWithoutRequest() {
        ReflectionTestUtils.setField(notifier, "accessToken", "");

        assertFalse(notifier.post("hello"));
        assertNull(lastRequestBody.get(), "без токена запрос вообще не должен уйти");
    }

    @Test
    void post_missingGroupId_returnsFalseWithoutRequest() {
        ReflectionTestUtils.setField(notifier, "groupId", "");

        assertFalse(notifier.post("hello"));
        assertNull(lastRequestBody.get(), "без group id запрос вообще не должен уйти");
    }
}
