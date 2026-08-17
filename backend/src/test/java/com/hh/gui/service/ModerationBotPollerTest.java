package com.hh.gui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModerationBotPollerTest {

    @Test
    void parseVacancyId_approveCallback_extractsId() {
        assertEquals(123L, ModerationBotPoller.parseVacancyId("modpub:123"));
    }

    @Test
    void parseVacancyId_rejectCallback_extractsId() {
        assertEquals(456L, ModerationBotPoller.parseVacancyId("modrej:456"));
    }

    @Test
    void parseVacancyId_noColon_returnsNull() {
        assertNull(ModerationBotPoller.parseVacancyId("garbage"));
    }

    @Test
    void parseVacancyId_nonNumericSuffix_returnsNullNotThrows() {
        assertDoesNotThrow(() -> assertNull(ModerationBotPoller.parseVacancyId("modpub:not-a-number")));
    }

    @Test
    void parseVacancyId_null_returnsNull() {
        assertNull(ModerationBotPoller.parseVacancyId(null));
    }
}
