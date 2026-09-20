package com.hh.gui.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Живой локальный HttpServer вместо мока — как в VkNotifierTest: проверяем, какую форму
 * сервис реально отправляет в VK ID и как переживает отказ. Часы фиксированные, чтобы
 * «до истечения 4 минуты» было утверждением, а не гонкой.
 */
class VkIdTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @TempDir Path dir;
    private HttpServer server;
    private String authUrl;
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String reply = "{\"access_token\":\"NEW_AT\",\"refresh_token\":\"NEW_RT\",\"expires_in\":3600,"
        + "\"user_id\":22019477,\"scope\":\"wall photos\",\"token_type\":\"Bearer\"}";
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth2/auth", ex -> {
            lastForm.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.incrementAndGet();
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        authUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/auth";
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Path tokenFile(long expiresAt) throws Exception {
        return tokenFile(expiresAt, "OLD_AT", "OLD_RT");
    }

    private Path tokenFile(long expiresAt, String accessToken, String refreshToken) throws Exception {
        Path f = dir.resolve("vk-id-token.json");
        Files.writeString(f, new ObjectMapper().writeValueAsString(Map.of(
            "access_token", accessToken, "refresh_token", refreshToken, "device_id", "DEV1",
            "state", "st1", "expires_at", expiresAt, "user_id", 22019477, "scope", "wall photos")));
        return f;
    }

    private VkIdTokenService service(Path file, Instant now) {
        return new VkIdTokenService("54780855", file, authUrl, Clock.fixed(now, ZoneOffset.UTC), registry, null);
    }

    @Test
    void notConfigured_returnsEmptyAndNeverCallsVk() throws Exception {
        VkIdTokenService s = new VkIdTokenService("", tokenFile(NOW.getEpochSecond() + 3600), authUrl,
            Clock.fixed(NOW, ZoneOffset.UTC), registry, null);
        assertTrue(s.accessToken().isEmpty());
        assertFalse(s.configured());
        assertEquals(0, calls.get());
    }

    @Test
    void missingFile_returnsEmpty() {
        VkIdTokenService s = service(dir.resolve("absent.json"), NOW);
        assertTrue(s.accessToken().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void freshToken_isReturnedWithoutRefresh() throws Exception {
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 3000), NOW);
        assertEquals("OLD_AT", s.accessToken().orElseThrow());
        assertEquals(0, calls.get(), "токен с запасом 50 минут обновлять незачем");
    }

    @Test
    void nearlyExpired_refreshesWithRotatedPairAndRewritesFile() throws Exception {
        Path f = tokenFile(NOW.getEpochSecond() + 200);
        VkIdTokenService s = service(f, NOW);

        assertEquals("NEW_AT", s.accessToken().orElseThrow());
        assertEquals(1, calls.get());
        String form = lastForm.get();
        assertTrue(form.contains("grant_type=refresh_token"), form);
        assertTrue(form.contains("refresh_token=OLD_RT"), form);
        assertTrue(form.contains("client_id=54780855"), form);
        assertTrue(form.contains("device_id=DEV1"), "device_id обязателен для VK ID: " + form);
        assertTrue(form.contains("state=st1"), form);

        Map<?, ?> saved = new ObjectMapper().readValue(Files.readString(f), Map.class);
        assertEquals("NEW_AT", saved.get("access_token"));
        assertEquals("NEW_RT", saved.get("refresh_token"), "refresh-токен ротируется — старый уже недействителен, хранить надо новый");
        assertEquals("DEV1", saved.get("device_id"));
        assertEquals(NOW.getEpochSecond() + 3600, ((Number) saved.get("expires_at")).longValue());
        assertEquals(1.0, registry.counter("vk_id_token_refresh_total", "result", "ok").count());
    }

    @Test
    void refreshIfDue_refreshesOnlyInsideTheAheadWindow() throws Exception {
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 3500), NOW);
        s.refreshIfDue();
        assertEquals(0, calls.get(), "58 минут запаса — рано");

        VkIdTokenService s2 = service(tokenFile(NOW.getEpochSecond() + 1500), NOW);
        s2.refreshIfDue();
        assertEquals(1, calls.get(), "25 минут запаса — пора");
    }

    @Test
    void rejectedRefresh_keepsOldTokenWhileItLives_thenGoesEmpty() throws Exception {
        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}";
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 200), NOW);

        assertEquals("OLD_AT", s.accessToken().orElseThrow(), "пока старый жив — отдаём его, а не ломаем публикацию");
        assertEquals(1.0, registry.counter("vk_id_token_refresh_total", "result", "rejected").count());

        VkIdTokenService later = service(tokenFile(NOW.getEpochSecond() - 1), NOW);
        assertTrue(later.accessToken().isEmpty(), "просроченный токен не отдаём никогда");
    }

    @Test
    void transportFailure_isCountedAsErrorNotRejected() throws Exception {
        server.stop(0);
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 200), NOW);
        assertEquals("OLD_AT", s.accessToken().orElseThrow());
        assertEquals(1.0, registry.counter("vk_id_token_refresh_total", "result", "error").count());
    }

    @Test
    void expiresGauge_reflectsRemainingSeconds() throws Exception {
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 1234), NOW);
        s.accessToken();
        assertEquals(1234.0, registry.get("vk_id_token_expires_in_seconds").gauge().value());
    }

    // ── режим «обновляют снаружи» (vk-token-refresh.js, refresh_token пустой) ──

    @Test
    void externallyRefreshed_neverCallsVkId_evenWhenExpiring() throws Exception {
        VkIdTokenService s = service(tokenFile(NOW.getEpochSecond() + 100, "EXT_AT", ""), NOW);
        assertEquals("EXT_AT", s.accessToken().orElseThrow());
        s.refreshIfDue();
        assertEquals(0, calls.get(), "без refresh-токена обменивать нечего — в VK ID не ходим");
    }

    @Test
    void fileRewrittenByExternalScript_isPickedUpWithoutRestart() throws Exception {
        Path f = tokenFile(NOW.getEpochSecond() + 3000, "FIRST", "");
        VkIdTokenService s = service(f, NOW);
        assertEquals("FIRST", s.accessToken().orElseThrow());

        Files.writeString(f, new ObjectMapper().writeValueAsString(Map.of(
            "access_token", "SECOND", "refresh_token", "", "device_id", "", "state", "",
            "expires_at", NOW.getEpochSecond() + 86400, "user_id", 22019477, "scope", "photos wall")));
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertEquals("SECOND", s.accessToken().orElseThrow(), "новый файл от внешнего скрипта должен подхватиться по mtime");
        assertEquals(0, calls.get());
    }

    @Test
    void externallyRefreshed_expiredAndNotReplaced_alertsOwnerOnce() throws Exception {
        java.util.List<String> sent = new java.util.ArrayList<>();
        TelegramNotifier notifier = new TelegramNotifier() {
            @Override public boolean send(String message) { sent.add(message); return true; }
        };
        VkIdTokenService s = new VkIdTokenService("54780855", tokenFile(NOW.getEpochSecond() + 60, "EXT_AT", ""),
            authUrl, Clock.fixed(NOW, ZoneOffset.UTC), registry, notifier);

        s.refreshIfDue();
        s.refreshIfDue();

        assertEquals(1, sent.size(), "владельцу — одно предупреждение, не по одному на каждый тик");
        assertTrue(sent.get(0).contains("vk-token-refresh"), sent.get(0));
        assertEquals("EXT_AT", s.accessToken().orElseThrow(), "пока минута есть — токен ещё отдаём");
    }
}
