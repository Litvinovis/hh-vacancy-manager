package com.hh.gui.ai;

/**
 * A failed LLM call, carrying WHY it failed as data.
 *
 * Callers used to classify by substring-matching the exception message
 * ({@code msg.contains("429")}), which silently mis-handled every failure whose text
 * didn't happen to embed an HTTP status. Observed live on 2026-08-13: the provider
 * answered HTTP 200 with a payload that had no {@code choices}, the analyzer raised
 * a plain RuntimeException("Ответ AI не содержит choices"), that matched neither the
 * rate-limit nor the auth branch — so it retried the same provider three times and
 * gave up without ever trying the configured fallback, abandoning the batch.
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** HTTP 429, or a provider-specific "slow down" — the same provider may recover shortly. */
        RATE_LIMIT,
        /** HTTP 401/403 — credentials or entitlement are wrong; retrying the same provider cannot help. */
        AUTH,
        /**
         * HTTP 403, но отданный не самим API, а щитом перед ним (Cloudflare и подобные):
         * блокировка нашего адреса, а не проблема ключа или модели. Отличается по телу
         * ответа и заголовку server; см. {@link #looksLikeEdgeBlock}. Важно не путать с
         * AUTH: 18.09.2026 такие 403 заставляли FreeModelUpdater выбрасывать рабочие
         * бесплатные модели, а анализатор — считать ключ негодным, хотя мешал только щит.
         * Лечится не сменой провайдера, а паузой и повтором.
         */
        EDGE_BLOCKED,
        /** Transport-level: connect/read timeout, DNS, connection reset. No usable HTTP status. */
        TRANSPORT,
        /** A 2xx response whose body we cannot use: no choices, empty content, no JSON array, truncated. */
        BAD_RESPONSE,
        /** Any other HTTP error status (5xx, unexpected 4xx). */
        HTTP_ERROR
    }

    private final Kind kind;
    /** HTTP status when one was received, 0 for TRANSPORT failures. */
    private final int httpStatus;

    public LlmException(Kind kind, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public LlmException(Kind kind, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Maps an HTTP status to the kind the retry policy should act on. */
    public static Kind kindForStatus(int status) {
        if (status == 429) return Kind.RATE_LIMIT;
        if (status == 401 || status == 403) return Kind.AUTH;
        return Kind.HTTP_ERROR;
    }

    /**
     * Как {@link #kindForStatus}, но с телом ответа и заголовком server — единственным, чем
     * 403 от щита отличается от 403 от API. У OpenRouter ошибка всегда приходит объектом
     * {"error":{"message":...}}; страница блокировки — что-то своё, например
     * {"success":false,"error":"Access denied by security policy."} при server: cloudflare.
     */
    public static Kind kindForResponse(int status, String body, String serverHeader) {
        if (status == 403 && looksLikeEdgeBlock(body, serverHeader)) return Kind.EDGE_BLOCKED;
        return kindForStatus(status);
    }

    static boolean looksLikeEdgeBlock(String body, String serverHeader) {
        String server = serverHeader == null ? "" : serverHeader.toLowerCase();
        String text = body == null ? "" : body.toLowerCase();
        boolean shieldAnswered = server.contains("cloudflare") || server.contains("akamai") || server.contains("ddos");
        boolean apiShapedError = text.contains("\"error\"") && text.contains("\"message\"");
        // Щит отвечает своей страницей: либо его ни с чем не спутать по server, либо тело
        // не похоже на ошибку API. Сообщение про security policy ловим и без заголовка —
        // оно приходит от промежуточных провайдеров, которые server не выставляют.
        return (shieldAnswered && !apiShapedError) || text.contains("access denied by security policy");
    }
}
