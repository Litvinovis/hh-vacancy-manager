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

    // ── Gemini: «не тот регион выхода» ──

    private static final String GEMINI_GEO_400 =
        "[{\"error\":{\"code\":400,\"message\":\"User location is not supported for the API use.\","
        + "\"status\":\"FAILED_PRECONDITION\"}}]";

    @Test
    void gemini400AboutLocation_isGeoBlockedNotPlainHttpError() {
        assertEquals(LlmException.Kind.GEO_BLOCKED,
            LlmException.kindForResponse(400, GEMINI_GEO_400, null),
            "адрес выхода не подошёл — это повторяемо, а не ошибка запроса");
    }

    @Test
    void other400_staysHttpError() {
        assertEquals(LlmException.Kind.HTTP_ERROR,
            LlmException.kindForResponse(400, "{\"error\":{\"message\":\"Invalid JSON payload\"}}", null));
    }

    @Test
    void geoWordingIsMatchedCaseInsensitively() {
        assertTrue(LlmException.looksLikeGeoBlock("USER LOCATION IS NOT SUPPORTED for the API use."));
        assertFalse(LlmException.looksLikeGeoBlock("{\"error\":{\"message\":\"model not found\"}}"));
    }
}
