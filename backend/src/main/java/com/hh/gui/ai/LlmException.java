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
}
