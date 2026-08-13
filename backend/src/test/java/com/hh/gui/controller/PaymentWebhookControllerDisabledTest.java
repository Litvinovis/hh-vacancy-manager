package com.hh.gui.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default config has app.subscriptions.enabled=false — the endpoint must behave as if
 * it doesn't exist, same posture as TelegramBotPoller not starting its thread at all.
 * Separate class from PaymentWebhookControllerTest because @TestPropertySource there
 * flips the flag on for the whole class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentWebhookControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void featureDisabled_returns404EvenWithValidLookingRequest() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                .header("X-Webhook-Secret", "whatever")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"p\",\"metadata\":{\"telegramUserId\":\"1\"}}}"))
            .andExpect(status().isNotFound());
    }
}
