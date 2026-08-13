package com.hh.gui.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-username backoff for failed logins. Nothing rate-limited /api/auth/login before
 * this, so a weak password (the seeded admin one is short by design) could be walked
 * through offline-speed guessing over the LAN.
 *
 * Keyed by username rather than client IP on purpose: this app sits behind no proxy
 * and is reached from a handful of devices, so an IP key would lock out the whole
 * household on one person's typo, while a username key isolates the account actually
 * under attack. In-memory only — a restart clears the counters, which is acceptable
 * for a brute-force speed bump (a restart isn't something an attacker can trigger).
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    // Entries idle far longer than the lockout are pure garbage; swept lazily on
    // access so a long-running process can't accumulate one entry per attempted name.
    private static final Duration RETENTION = Duration.ofHours(1);

    private record Attempts(int failures, Instant lastFailure) {}

    private final Map<String, Attempts> byUsername = new ConcurrentHashMap<>();

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** True while this username is locked out; callers must not even check the password. */
    public boolean isLocked(String username) {
        Attempts a = byUsername.get(key(username));
        if (a == null) return false;
        if (a.failures() < MAX_FAILURES) return false;
        return a.lastFailure().plus(LOCKOUT).isAfter(Instant.now());
    }

    /** Seconds left on an active lockout — for the error message. 0 when not locked. */
    public long secondsRemaining(String username) {
        Attempts a = byUsername.get(key(username));
        if (a == null || a.failures() < MAX_FAILURES) return 0;
        long left = Duration.between(Instant.now(), a.lastFailure().plus(LOCKOUT)).toSeconds();
        return Math.max(0, left);
    }

    public void recordFailure(String username) {
        sweep();
        Instant now = Instant.now();
        byUsername.compute(key(username), (k, prev) -> {
            // A lockout that already expired starts the count over rather than leaving the
            // user one failure away from being re-locked for another full window.
            if (prev == null || prev.lastFailure().plus(LOCKOUT).isBefore(now)) {
                return new Attempts(1, now);
            }
            return new Attempts(prev.failures() + 1, now);
        });
    }

    public void recordSuccess(String username) {
        byUsername.remove(key(username));
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        byUsername.entrySet().removeIf(e -> e.getValue().lastFailure().isBefore(cutoff));
    }
}
