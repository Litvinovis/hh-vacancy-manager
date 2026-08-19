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
 * A real local HttpServer, not a mock — same reasoning as HttpUtilTest: this class's
 * whole job is talking HTTP to the Bot API, so the thing worth verifying is the actual
 * request it sends and how it parses a real response, not that some interface method
 * gets called. apiBaseUrl is set via ReflectionTestUtils (it's @Value-injected in
 * production, no constructor param) to redirect it at the local server instead of the
 * real api.telegram.org.
 */
class TelegramNotifierTest {

    private HttpServer server;
    private int port;
    private TelegramNotifier notifier;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/botGOODTOKEN/sendMessage", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            lastRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/botBADTOKEN/sendMessage", ex -> {
            byte[] body = "{\"ok\":false,\"description\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botGOODTOKEN/getChatMemberCount", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            byte[] body = "{\"ok\":true,\"result\":42}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botGOODTOKEN/getChat", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            byte[] body = "{\"ok\":true,\"result\":{\"username\":\"remotevibe\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botNOUSERNAME/getChat", ex -> {
            byte[] body = "{\"ok\":true,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botNOTOK/getChat", ex -> {
            byte[] body = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botNOTOK/getChatMemberCount", ex -> {
            byte[] body = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/bot500TOKEN/getChatMemberCount", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.createContext("/botGOODTOKEN/answerCallbackQuery", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/botGOODTOKEN/deleteMessage", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/botBADTOKEN/deleteMessage", ex -> {
            byte[] body = "{\"ok\":false,\"description\":\"Bad Request: message to delete not found\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/botBADTOKEN/answerCallbackQuery", ex -> {
            byte[] body = "{\"ok\":false,\"description\":\"Bad Request\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();

        notifier = new TelegramNotifier();
        ReflectionTestUtils.setField(notifier, "apiBaseUrl", "http://127.0.0.1:" + port);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ── send() / sendViaChannelBot() ──

    @Test
    void send_success_returnsTrue() {
        ReflectionTestUtils.setField(notifier, "botToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertTrue(notifier.send("hello"));
    }

    @Test
    void send_postsChatIdAndTextUrlEncoded() {
        ReflectionTestUtils.setField(notifier, "botToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        notifier.send("привет мир");

        assertTrue(lastRequestBody.get().contains("chat_id=-100123"));
        assertTrue(lastRequestBody.get().contains("text="), "текст должен уйти в теле запроса");
        assertFalse(lastRequestBody.get().contains("привет"), "кириллица должна быть URL-encoded, не сырой");
    }

    @Test
    void send_targetChatIdOverridesConfiguredDefault() {
        ReflectionTestUtils.setField(notifier, "botToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        notifier.send("hello", "-100999");

        assertTrue(lastRequestBody.get().contains("chat_id=-100999"));
    }

    @Test
    void send_blankTargetChatId_fallsBackToConfiguredDefault() {
        ReflectionTestUtils.setField(notifier, "botToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        notifier.send("hello", "");

        assertTrue(lastRequestBody.get().contains("chat_id=-100123"));
    }

    @Test
    void send_missingToken_returnsFalseWithoutHttpCall() {
        ReflectionTestUtils.setField(notifier, "botToken", "");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertFalse(notifier.send("hello"));
        assertNull(lastPath.get(), "без токена запрос вообще не должен уйти");
    }

    @Test
    void send_missingChatId_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "botToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "");

        assertFalse(notifier.send("hello"));
    }

    @Test
    void send_apiRejects_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "botToken", "BADTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertFalse(notifier.send("hello"));
    }

    @Test
    void sendViaChannelBot_usesChannelToken_notPersonalToken() {
        ReflectionTestUtils.setField(notifier, "botToken", "BADTOKEN"); // personal token would fail
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertTrue(notifier.sendViaChannelBot("post", "-100123"),
            "должен использовать channel-bot-token, а не личный botToken");
    }

    @Test
    void sendViaChannelBot_blankTargetChatId_noFallback_returnsFalse() {
        // Unlike send(), a channel destination is always explicit — no app.telegram.chat-id fallback.
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertFalse(notifier.sendViaChannelBot("post", ""));
    }

    // ── getChatMemberCount() ──

    @Test
    void getChatMemberCount_success_returnsCount() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertEquals(42, notifier.getChatMemberCount("-100123"));
    }

    @Test
    void getChatMemberCount_missingToken_returnsNullWithoutHttpCall() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "");

        assertNull(notifier.getChatMemberCount("-100123"));
        assertNull(lastPath.get());
    }

    @Test
    void getChatMemberCount_blankChatId_returnsNull() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertNull(notifier.getChatMemberCount(""));
    }

    @Test
    void getChatMemberCount_okFalse_returnsNull() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "NOTOK");

        assertNull(notifier.getChatMemberCount("-100123"));
    }

    @Test
    void getChatMemberCount_httpError_returnsNull() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "500TOKEN");

        assertNull(notifier.getChatMemberCount("-100123"));
    }

    @Test
    void getChatMemberCount_botNotAdminOrNetworkDown_returnsNullNotException() {
        // Points at a port nothing listens on — simulates a network failure. The method's
        // whole contract (see its javadoc) is "null on any failure, never an exception" —
        // the caller (a periodic sweep over several channels) must not have one bad
        // channel kill the whole tick.
        ReflectionTestUtils.setField(notifier, "apiBaseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertDoesNotThrow(() -> assertNull(notifier.getChatMemberCount("-100123")));
    }

    // ── getChatUsername() ──

    @Test
    void getChatUsername_success_returnsUsername() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertEquals("remotevibe", notifier.getChatUsername("-100123"));
    }

    @Test
    void getChatUsername_noUsernameOnChannel_returnsNull() {
        // A fully private channel (no public @username) — not a failure, just nothing to return.
        ReflectionTestUtils.setField(notifier, "channelBotToken", "NOUSERNAME");

        assertNull(notifier.getChatUsername("-100123"));
    }

    @Test
    void getChatUsername_okFalse_returnsNull() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "NOTOK");

        assertNull(notifier.getChatUsername("-100123"));
    }

    @Test
    void getChatUsername_missingToken_returnsNull() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "");

        assertNull(notifier.getChatUsername("-100123"));
    }

    // ── moderation cards (sent via the CHANNEL bot, see sendModerationCardBatch's javadoc for why) ──

    @Test
    void sendModerationCardBatch_singleVacancy_sameShapeAsOldSingleCard() {
        // Батч из одной вакансии — ровно то, чем раньше была единственная карточка
        // (см. ModerationService: "по одной" всё ещё держится, просто через batch=1).
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertTrue(notifier.sendModerationCardBatch("Оператор поддержки", java.util.List.of(42L)));

        assertTrue(lastPath.get().contains("/botGOODTOKEN/sendMessage"), "должен уйти через канальный бот, не личный");
        assertTrue(lastRequestBody.get().contains("chat_id=-100123"));
        assertTrue(lastRequestBody.get().contains("modpub%3A42"), "callback_data кнопки \"Опубликовать\" должна нести id вакансии");
        assertTrue(lastRequestBody.get().contains("modrej%3A42"), "callback_data кнопки \"Отклонить\" должна нести id вакансии");
        assertFalse(lastRequestBody.get().contains("modpuball"),
            "единственная вакансия в батче — кнопка \"одобрить всё\" была бы дублем");
    }

    @Test
    void sendModerationCardBatch_multipleVacancies_includesPerItemButtonsAndApproveAll() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertTrue(notifier.sendModerationCardBatch("Батч из трёх", java.util.List.of(1L, 2L, 3L)));

        assertTrue(lastRequestBody.get().contains("modpub%3A1"));
        assertTrue(lastRequestBody.get().contains("modrej%3A1"));
        assertTrue(lastRequestBody.get().contains("modpub%3A2"));
        assertTrue(lastRequestBody.get().contains("modpub%3A3"));
        assertTrue(lastRequestBody.get().contains("modpuball"), "батч из нескольких вакансий должен нести кнопку \"одобрить всё\"");
    }

    @Test
    void sendModerationCardBatch_missingChannelToken_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertFalse(notifier.sendModerationCardBatch("Текст", java.util.List.of(1L, 2L)));
    }

    @Test
    void sendModerationCardBatch_missingChatId_returnsFalse() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "");

        assertFalse(notifier.sendModerationCardBatch("Текст", java.util.List.of(1L)));
    }

    @Test
    void deleteModerationCard_success_sendsChatIdAndMessageId() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertDoesNotThrow(() -> notifier.deleteModerationCard(555L));

        assertTrue(lastPath.get().contains("/botGOODTOKEN/deleteMessage"));
        assertTrue(lastPath.get().contains("chat_id=-100123"));
        assertTrue(lastPath.get().contains("message_id=555"));
    }

    @Test
    void deleteModerationCard_failure_isBestEffort_doesNotThrow() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "BADTOKEN");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertDoesNotThrow(() -> notifier.deleteModerationCard(555L));
    }

    @Test
    void deleteModerationCard_missingToken_doesNotThrow() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "");
        ReflectionTestUtils.setField(notifier, "chatId", "-100123");

        assertDoesNotThrow(() -> notifier.deleteModerationCard(555L));
    }

    @Test
    void answerCallbackQuery_success_doesNotThrow() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "GOODTOKEN");

        assertDoesNotThrow(() -> notifier.answerCallbackQuery("query-id-1"));
        assertTrue(lastPath.get().contains("/botGOODTOKEN/answerCallbackQuery"));
    }

    @Test
    void answerCallbackQuery_failure_isBestEffort_doesNotThrow() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "BADTOKEN");

        assertDoesNotThrow(() -> notifier.answerCallbackQuery("query-id-1"));
    }

    @Test
    void answerCallbackQuery_missingToken_doesNotThrow() {
        ReflectionTestUtils.setField(notifier, "channelBotToken", "");

        assertDoesNotThrow(() -> notifier.answerCallbackQuery("query-id-1"));
    }
}
