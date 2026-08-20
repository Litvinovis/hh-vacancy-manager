package com.hh.gui.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP fixed-window limiter for the unauthenticated /go/{token} click-tracking
 * redirect (see ClickTrackingController) — that endpoint is deliberately open to
 * anyone (real Telegram readers aren't logged into this app), which also means a
 * script can hit it in a tight loop with no gate at all, hammering the DB lookup/
 * insert on every hit and inflating the vacancy_click_total metric. Same in-memory,
 * restart-clears-it approach as LoginThrottle — a speed bump, not a durable ledger.
 */
@Component
public class ClickRateLimiter {

    public static final int MAX_REQUESTS_PER_WINDOW = 30;
    // Not static/final — tests shrink this to exercise the window-rollover path without
    // actually waiting a minute (see ClickRateLimiterTest).
    private Duration window = Duration.ofMinutes(1);
    private static final Duration RETENTION = Duration.ofHours(1);

    private record Window(AtomicInteger count, Instant windowStart) {}

    private final Map<String, Window> byIp = new ConcurrentHashMap<>();

    /** True if this request should proceed; false once the caller's IP has used up
     *  its window — the caller returns 429 without touching the DB at all. */
    public boolean allow(String ip) {
        sweep();
        String key = ip == null ? "" : ip;
        Instant now = Instant.now();
        Window w = byIp.compute(key, (k, prev) -> {
            if (prev == null || prev.windowStart().plus(window).isBefore(now)) {
                return new Window(new AtomicInteger(1), now);
            }
            prev.count().incrementAndGet();
            return prev;
        });
        return w.count().get() <= MAX_REQUESTS_PER_WINDOW;
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        byIp.entrySet().removeIf(e -> e.getValue().windowStart().isBefore(cutoff));
    }
}
