package com.hh.gui.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real local HttpServer, not a mock — same reasoning as VkNotifierTest: what's worth
 * verifying is the actual request (owner_id negation, query encoding) and how a VK-level
 * error is told apart from success, both of which arrive as HTTP 200 (see VkNotifier's
 * javadoc — VK never uses a non-200 status for API errors).
 */
class VkReaderClientTest {

    private HttpServer server;
    private int port;
    private VkReaderClient client;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/method/wall.get", ex -> {
            String query = ex.getRequestURI().getQuery();
            lastQuery.set(query);
            byte[] body;
            if (query.contains("access_token=BADTOKEN")) {
                body = "{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}".getBytes(StandardCharsets.UTF_8);
            } else if (query.contains("owner_id=-500")) {
                body = ("{\"response\":{\"count\":1,\"items\":[{\"id\":789,\"owner_id\":-500,"
                    + "\"text\":\"Где искать удалённую работу?\",\"date\":1700000000}]}}").getBytes(StandardCharsets.UTF_8);
            } else if (query.contains("owner_id=-600")) {
                // Malformed item: missing owner_id — must be skipped, not throw.
                body = "{\"response\":{\"count\":1,\"items\":[{\"id\":1,\"text\":\"broken\"}]}}".getBytes(StandardCharsets.UTF_8);
            } else if (query.contains("owner_id=-700")) {
                body = "{\"response\":{}}".getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"response\":{\"count\":0,\"items\":[]}}".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();

        client = new VkReaderClient();
        ReflectionTestUtils.setField(client, "apiBaseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(client, "userReadToken", "GOODTOKEN");
        ReflectionTestUtils.setField(client, "apiVersion", "5.199");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchWallPosts_success_returnsParsedPosts() {
        List<VkReaderClient.VkPost> posts = client.fetchWallPosts("500", 10);

        assertEquals(1, posts.size());
        VkReaderClient.VkPost post = posts.get(0);
        assertEquals("wall-500_789", post.postId());
        assertEquals("Где искать удалённую работу?", post.text());
        assertEquals(1700000000L, post.date());
        assertEquals("https://vk.com/wall-500_789", post.link());
    }

    @Test
    void fetchWallPosts_negatesCommunityIdForOwnerId() {
        client.fetchWallPosts("500", 10);

        assertTrue(lastQuery.get().contains("owner_id=-500"),
            "wall.get's owner_id must be the NEGATED community id (VK convention for a community)");
    }

    @Test
    void fetchWallPosts_itemMissingOwnerId_skippedNotThrown() {
        List<VkReaderClient.VkPost> posts = assertDoesNotThrow(() -> client.fetchWallPosts("600", 10));

        assertTrue(posts.isEmpty());
    }

    @Test
    void fetchWallPosts_responseWithNoItemsField_returnsEmptyListNotThrows() {
        List<VkReaderClient.VkPost> posts = assertDoesNotThrow(() -> client.fetchWallPosts("700", 10));

        assertTrue(posts.isEmpty());
    }

    @Test
    void fetchWallPosts_vkApiErrorObjectInHttp200Body_returnsEmptyList() {
        ReflectionTestUtils.setField(client, "userReadToken", "BADTOKEN");

        assertTrue(client.fetchWallPosts("500", 10).isEmpty(),
            "VK отвечает 200 даже на ошибку API — тело нужно парсить в любом случае");
    }

    @Test
    void fetchWallPosts_httpErrorStatus_returnsEmptyList() {
        // VkReaderClient always calls the fixed path "/method/wall.get" under apiBaseUrl —
        // pointing that at a base with no matching context is the simplest way to force a
        // real non-200 status (404) rather than a VK-shaped error body.
        ReflectionTestUtils.setField(client, "apiBaseUrl", "http://127.0.0.1:" + port + "/no-such-base");

        assertTrue(client.fetchWallPosts("500", 10).isEmpty());
    }

    @Test
    void fetchWallPosts_missingToken_returnsEmptyListWithoutRequest() {
        ReflectionTestUtils.setField(client, "userReadToken", "");

        assertTrue(client.fetchWallPosts("500", 10).isEmpty());
        assertNull(lastQuery.get(), "без токена запрос вообще не должен уйти");
    }
}
