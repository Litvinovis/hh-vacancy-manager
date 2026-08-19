package com.hh.gui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModerationBotPollerTest {

    @TempDir
    Path tempDir;

    private ModerationBotPoller pollerWithDataDir() {
        ModerationBotPoller p = new ModerationBotPoller(null, null, null);
        ReflectionTestUtils.setField(p, "dataDir", tempDir.toString());
        return p;
    }

    private long loadOffset(ModerationBotPoller p) throws Exception {
        Method m = ModerationBotPoller.class.getDeclaredMethod("loadOffset");
        m.setAccessible(true);
        return (long) m.invoke(p);
    }

    private void saveOffset(ModerationBotPoller p, long value) throws Exception {
        Method m = ModerationBotPoller.class.getDeclaredMethod("saveOffset", long.class);
        m.setAccessible(true);
        m.invoke(p, value);
    }

    // ── offset persistence (see class javadoc — the whole point of this session's fix:
    //    an in-memory-only offset resets on every restart and replays Telegram's
    //    backlog of already-applied callback_query taps) ──

    @Test
    void loadOffset_noFileYet_returnsZero() throws Exception {
        assertEquals(0, loadOffset(pollerWithDataDir()));
    }

    @Test
    void saveOffset_thenLoadOffset_roundTrips() throws Exception {
        ModerationBotPoller p = pollerWithDataDir();
        saveOffset(p, 424242L);

        assertEquals(424242L, loadOffset(p));
    }

    @Test
    void loadOffset_survivesAcrossDifferentPollerInstances() throws Exception {
        // The real scenario: the process restarts, a brand-new ModerationBotPoller
        // instance is constructed — it must pick up where the last one left off.
        saveOffset(pollerWithDataDir(), 99L);

        assertEquals(99L, loadOffset(pollerWithDataDir()));
    }

    @Test
    void loadOffset_corruptFile_returnsZeroNotThrows() throws Exception {
        Files.writeString(tempDir.resolve("moderation-offset.txt"), "not-a-number");

        assertEquals(0, loadOffset(pollerWithDataDir()));
    }

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

    @Test
    void parseVacancyId_approveAllCallback_returnsNull() {
        // "modpuball" carries no vacancy id at all — handleUpdate checks for it as a
        // literal BEFORE calling parseVacancyId (see ModerationService.resolveApproveAll),
        // this just documents that the general parser correctly has nothing to say about it.
        assertNull(ModerationBotPoller.parseVacancyId("modpuball"));
    }
}
