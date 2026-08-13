package com.hh.gui.controller;

import com.hh.gui.model.Subscription;
import com.hh.gui.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.subscriptions.enabled=true",
    "app.subscriptions.webhook-secret=test-secret"
})
class PaymentWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubscriptionRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM subscriptions");
    }

    private void savePending(long userId) {
        Subscription s = new Subscription();
        s.setTelegramUserId(userId);
        s.setTelegramChatId(userId);
        s.setStatus(Subscription.STATUS_PENDING);
        repo.save(s);
    }

    private String succeededPayload(long userId, String paymentId) {
        return """
            {"event":"payment.succeeded","object":{"id":"%s","metadata":{"telegramUserId":"%d"}}}
            """.formatted(paymentId, userId);
    }

    @Test
    void succeeded_withCorrectSecret_activatesSubscription() throws Exception {
        savePending(1L);

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededPayload(1L, "pay_1")))
            .andExpect(status().isOk());

        Subscription s = repo.findByTelegramUserId(1L).orElseThrow();
        assertEquals(Subscription.STATUS_ACTIVE, s.getStatus());
        assertEquals("pay_1", s.getExternalPaymentId());
    }

    @Test
    void succeeded_redelivered_doesNotExtendTwice() throws Exception {
        savePending(1L);
        String payload = succeededPayload(1L, "pay_1");

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk());
        String firstExpiry = repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt();

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk());

        assertEquals(firstExpiry, repo.findByTelegramUserId(1L).orElseThrow().getExpiresAt(),
            "повторная доставка того же payment_id не должна продлевать подписку второй раз");
    }

    @Test
    void wrongSecret_returns401_doesNotActivate() throws Exception {
        savePending(1L);

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "неверный")
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededPayload(1L, "pay_1")))
            .andExpect(status().isUnauthorized());

        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void missingSecretHeader_returns401() throws Exception {
        savePending(1L);

        mockMvc.perform(post("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededPayload(1L, "pay_1")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void canceled_leavesStatusPending_sendsNoActivation() throws Exception {
        savePending(1L);
        String payload = """
            {"event":"payment.canceled","object":{"id":"pay_1","metadata":{"telegramUserId":"1"}}}
            """;

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk());

        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void unknownEvent_returns200_isIgnored() throws Exception {
        savePending(1L);
        String payload = """
            {"event":"payment.waiting_for_capture","object":{"id":"pay_1","metadata":{"telegramUserId":"1"}}}
            """;

        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk());

        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(1L).orElseThrow().getStatus());
    }

    @Test
    void missingObject_returns400() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"payment.succeeded\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void missingTelegramUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"pay_1\"}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void noSubscriptionRowForUser_returns404() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(succeededPayload(999L, "pay_1")))
            .andExpect(status().isNotFound());
    }
}
