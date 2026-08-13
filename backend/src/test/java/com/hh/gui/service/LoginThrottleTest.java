package com.hh.gui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {

    @Test
    void locksOnlyAfterThresholdReached() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            t.recordFailure("admin");
            assertFalse(t.isLocked("admin"), "до порога вход должен оставаться открытым");
        }
        t.recordFailure("admin");
        assertTrue(t.isLocked("admin"), "на пороге вход должен блокироваться");
        assertTrue(t.secondsRemaining("admin") > 0);
    }

    @Test
    void successfulLoginClearsCounter() {
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) t.recordFailure("admin");
        assertTrue(t.isLocked("admin"));

        t.recordSuccess("admin");

        assertFalse(t.isLocked("admin"), "успешный вход должен снимать блокировку");
        assertEquals(0, t.secondsRemaining("admin"));
    }

    @Test
    void lockoutIsPerUsername() {
        // Ключ по имени, а не по IP: перебор одной учётки не должен запирать остальных
        // пользователей дома, выходящих с того же адреса.
        LoginThrottle t = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) t.recordFailure("admin");

        assertTrue(t.isLocked("admin"));
        assertFalse(t.isLocked("мама"), "блокировка одной учётки не должна задевать другие");
    }

    @Test
    void usernameKeyIsCaseAndWhitespaceInsensitive() {
        // Иначе перебор обходился бы простым чередованием "admin"/"Admin"/" admin ".
        LoginThrottle t = new LoginThrottle();
        t.recordFailure("admin");
        t.recordFailure("ADMIN");
        t.recordFailure(" Admin ");
        t.recordFailure("aDmIn");
        t.recordFailure("admin ");

        assertTrue(t.isLocked("admin"), "регистр и пробелы не должны давать обход счётчика");
    }

    @Test
    void unknownUsernameIsNotLocked() {
        assertFalse(new LoginThrottle().isLocked("никогда-не-пробовали"));
    }
}
