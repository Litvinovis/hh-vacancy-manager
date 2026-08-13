package com.hh.gui.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Small helpers shared by the hand-rolled HttpURLConnection clients (no HTTP library dependency in this project). */
public final class HttpUtil {

    private HttpUtil() {}

    /**
     * Reads the full response body as UTF-8 text, from the error stream for 4xx/5xx and
     * the input stream otherwise.
     *
     * Returns "" when there is no body to read: getErrorStream() is null whenever the
     * server sends a bare status line with no payload, and wrapping that null used to
     * throw NullPointerException from inside the reader. That mattered well beyond the
     * lost message — callers classify failures by what the thrown exception says
     * (VacancyAiAnalyzer.analyzeWithRetry matches "429"/"401"/"403"), so a bodyless 429
     * surfaced as an NPE, matched nothing, and the provider-fallback chain never engaged.
     */
    public static String readBody(HttpURLConnection conn, int code) throws IOException {
        var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
