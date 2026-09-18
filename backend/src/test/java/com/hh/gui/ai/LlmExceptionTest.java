package com.hh.gui.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Отличить 403 от API (ключ/доступ) от 403 щита перед API (заблокирован наш адрес).
 * Реакция на них противоположная: в первом случае чинить доступ и не повторять,
 * во втором — подождать и повторить тем же ключом и той же моделью.
 */
class LlmExceptionTest {

    private static final String OPENROUTER_403 =
        "{\"error\":{\"message\":\"No auth credentials found\",\"code\":403}}";
    private static final String CLOUDFLARE_BLOCK =
        "{ \"success\": false, \"error\": \"Access denied by security policy.\" }";

    @Test
    void apiShapedForbidden_isAuth() {
        assertEquals(LlmException.Kind.AUTH,
            LlmException.kindForResponse(403, OPENROUTER_403, "cloudflare"),
            "у ответа форма ошибки API — значит ответило само API, пусть и через Cloudflare");
    }

    @Test
    void shieldPageForbidden_isEdgeBlocked() {
        assertEquals(LlmException.Kind.EDGE_BLOCKED,
            LlmException.kindForResponse(403, CLOUDFLARE_BLOCK, "cloudflare"));
    }

    @Test
    void securityPolicyBody_isEdgeBlocked_evenWithoutServerHeader() {
        // Промежуточные провайдеры отдают ту же страницу, не выставляя server
        assertEquals(LlmException.Kind.EDGE_BLOCKED,
            LlmException.kindForResponse(403, CLOUDFLARE_BLOCK, null));
    }

    @Test
    void unauthorized_staysAuth() {
        assertEquals(LlmException.Kind.AUTH,
            LlmException.kindForResponse(401, "{\"error\":{\"message\":\"bad key\"}}", "cloudflare"));
    }

    @Test
    void tooManyRequests_staysRateLimit() {
        assertEquals(LlmException.Kind.RATE_LIMIT,
            LlmException.kindForResponse(429, "{\"error\":{\"message\":\"slow down\"}}", "cloudflare"));
    }

    @Test
    void detectsShieldOnlyWhenBodyIsNotAnApiError() {
        assertTrue(LlmException.looksLikeEdgeBlock("<html>attention required</html>", "cloudflare"));
        assertFalse(LlmException.looksLikeEdgeBlock(OPENROUTER_403, "cloudflare"));
        assertFalse(LlmException.looksLikeEdgeBlock("{\"error\":{\"message\":\"x\"}}", "nginx"));
    }
}
