package com.hh.gui.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ClickRateLimiterTest {

    @Test
    void allow_underLimit_alwaysAllowed() {
        ClickRateLimiter limiter = new ClickRateLimiter();
        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            assertTrue(limiter.allow("1.2.3.4"));
        }
    }

    @Test
    void allow_overLimit_blocked() {
        ClickRateLimiter limiter = new ClickRateLimiter();
        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.allow("1.2.3.4");
        }
        assertFalse(limiter.allow("1.2.3.4"), "запрос сверх лимита в том же окне должен быть отклонён");
    }

    @Test
    void allow_differentIps_trackedIndependently() {
        ClickRateLimiter limiter = new ClickRateLimiter();
        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.allow("1.2.3.4");
        }
        assertTrue(limiter.allow("5.6.7.8"), "лимит одного IP не должен влиять на другой");
    }

    @Test
    void allow_nullIp_treatedAsSingleSharedKeyNotACrash() {
        ClickRateLimiter limiter = new ClickRateLimiter();
        assertTrue(limiter.allow(null));
    }

    @Test
    void allow_windowElapsed_countResetsAndRequestsAllowedAgain() throws InterruptedException {
        ClickRateLimiter limiter = new ClickRateLimiter();
        ReflectionTestUtils.setField(limiter, "window", Duration.ofMillis(50));

        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.allow("1.2.3.4");
        }
        assertFalse(limiter.allow("1.2.3.4"), "лимит исчерпан в текущем окне");

        Thread.sleep(100); // дождаться истечения искусственно укороченного окна

        assertTrue(limiter.allow("1.2.3.4"), "новое окно — счётчик должен сброситься");
    }
}
