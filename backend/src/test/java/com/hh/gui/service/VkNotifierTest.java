package com.hh.gui.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

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
    private final AtomicReference<String> lastUploadServerBody = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicReference<String> lastSaveWallPhotoBody = new AtomicReference<>();
    /** Сколько первых загрузок PNG сервер VK «примет», но вернёт пустое photo. */
    private volatile int emptyPhotoUploads;
    @TempDir Path dir;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
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
        server.createContext("/method/photos.getWallUploadServer", ex -> {
            lastUploadServerBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"response\":{\"upload_url\":\"http://127.0.0.1:" + port + "/upload\"}}")
                .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/upload", ex -> {
            ex.getRequestBody().readAllBytes();
            boolean empty = uploads.incrementAndGet() <= emptyPhotoUploads;
            byte[] body = ("{\"server\":906618,\"photo\":" + (empty ? "\"\"" : "\"[{\\\"photo\\\":\\\"abc\\\"}]\"")
                + ",\"hash\":\"h\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/method/photos.saveWallPhoto", ex -> {
            lastSaveWallPhotoBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"response\":[{\"owner_id\":-123456789,\"id\":42}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

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

    // ── пользовательский токен для картинок ──

    private VkIdTokenService vkIdWithToken(String accessToken) throws Exception {
        Path f = dir.resolve("vk-id-token.json");
        Files.writeString(f, new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "access_token", accessToken, "refresh_token", "RT", "device_id", "DEV", "state", "st",
            "expires_at", Clock.systemUTC().instant().getEpochSecond() + 3600, "user_id", 1, "scope", "wall photos")));
        return new VkIdTokenService("1", f, "http://127.0.0.1:" + port + "/oauth2/auth", Clock.systemUTC(), null, null);
    }

    @Test
    void uploadWallPhoto_prefersLiveVkIdTokenOverStaticOne() throws Exception {
        ReflectionTestUtils.setField(notifier, "photoUploadToken", "STATIC_USER_TOKEN");
        notifier.setVkIdTokens(vkIdWithToken("VKID_LIVE_TOKEN"));

        notifier.uploadWallPhoto(new byte[]{1, 2, 3}, "card.png");

        assertNotNull(lastUploadServerBody.get(), "photos.getWallUploadServer должен быть вызван");
        assertTrue(lastUploadServerBody.get().contains("access_token=VKID_LIVE_TOKEN"), lastUploadServerBody.get());
        assertTrue(lastUploadServerBody.get().contains("group_id=123456789"), lastUploadServerBody.get());
    }

    @Test
    void uploadWallPhoto_fallsBackToStaticTokenWhenVkIdHasNothing() throws Exception {
        ReflectionTestUtils.setField(notifier, "photoUploadToken", "STATIC_USER_TOKEN");
        notifier.setVkIdTokens(new VkIdTokenService("1", dir.resolve("absent.json"),
            "http://127.0.0.1:" + port + "/oauth2/auth", Clock.systemUTC(), null, null));

        notifier.uploadWallPhoto(new byte[]{1}, "card.png");

        assertTrue(lastUploadServerBody.get().contains("access_token=STATIC_USER_TOKEN"), lastUploadServerBody.get());
    }

    @Test
    void uploadWallPhoto_noUserTokenAtAll_returnsNullWithoutCallingVk() {
        assertNull(notifier.uploadWallPhoto(new byte[]{1}, "card.png"));
        assertNull(lastUploadServerBody.get(), "без пользовательского токена в VK ходить незачем");
    }

    @Test
    void uploadWallPhoto_happyPath_returnsAttachmentFromSaveWallPhoto() throws Exception {
        ReflectionTestUtils.setField(notifier, "photoUploadToken", "STATIC_USER_TOKEN");

        assertEquals("photo-123456789_42", notifier.uploadWallPhoto(new byte[]{1, 2, 3}, "card.png"));
        assertEquals(1, uploads.get());
        assertTrue(lastSaveWallPhotoBody.get().contains("server=906618"), lastSaveWallPhotoBody.get());
    }

    @Test
    void uploadWallPhoto_emptyPhotoFromUploadServer_isRetriedOnceBeforeSaving() throws Exception {
        // Живой случай 21.09.2026: upload_url ответил 200 с photo="", и saveWallPhoto упал
        // с error 100 «photo is undefined» — пост ушёл без карточки. Повтор загрузки спасает.
        ReflectionTestUtils.setField(notifier, "photoUploadToken", "STATIC_USER_TOKEN");
        emptyPhotoUploads = 1;

        assertEquals("photo-123456789_42", notifier.uploadWallPhoto(new byte[]{1, 2, 3}, "card.png"));
        assertEquals(2, uploads.get(), "первая загрузка пустая, вторая — рабочая");
        assertFalse(lastSaveWallPhotoBody.get().contains("photo=&"), "в saveWallPhoto не должно уходить пустое photo");
    }

    @Test
    void uploadWallPhoto_persistentlyEmptyPhoto_givesUpWithoutCallingSaveWallPhoto() throws Exception {
        ReflectionTestUtils.setField(notifier, "photoUploadToken", "STATIC_USER_TOKEN");
        emptyPhotoUploads = Integer.MAX_VALUE;

        assertNull(notifier.uploadWallPhoto(new byte[]{1, 2, 3}, "card.png"));
        assertEquals(2, uploads.get(), "две попытки — и хватит, пост уйдёт текстом");
        assertNull(lastSaveWallPhotoBody.get(), "с пустым photo в saveWallPhoto ходить бессмысленно");
    }
}
