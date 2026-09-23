package com.hh.gui.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModelHealthTest {

    @Test
    void failureRate_needsEnoughSamples_andUsesSlidingWindow() {
        ModelHealth h = new ModelHealth();
        for (int i = 0; i < ModelHealth.MIN_OUTCOMES_TO_JUDGE - 1; i++) h.recordOutcome("m", false);
        assertNull(h.failureRate("m"), "по девяти вызовам не судим");
        h.recordOutcome("m", false);
        assertEquals(1.0, h.failureRate("m"));
        assertTrue(h.failsInProduction("m"));
        for (int i = 0; i < ModelHealth.OUTCOME_WINDOW; i++) h.recordOutcome("m", true);
        assertEquals(0.0, h.failureRate("m"), "старые сбои вытесняются окном");
    }

    @Test
    void block_expires() {
        ModelHealth h = new ModelHealth();
        h.block("m", 1_000);
        assertTrue(h.isBlocked("m", 2_000));
        assertFalse(h.isBlocked("m", 1_000 + ModelHealth.BLOCK_DAYS * 24L * 3600 * 1000 + 1));
    }

    @Test
    void latency_isSmoothed_andUnknownSortsLast() {
        ModelHealth h = new ModelHealth();
        assertEquals(Double.MAX_VALUE, h.latencyMs("m"));
        h.recordLatency("m", 1000);
        h.recordLatency("m", 2000);
        assertEquals(1300, h.latencyMs("m"), 0.001);
    }

    @Test
    void survivesRestart(@TempDir Path dir) {
        ModelHealth before = new ModelHealth(dir.toString());
        before.recordTransientFailure("m");
        before.recordTransientFailure("m");
        before.recordOutcome("m", false);
        before.block("x", System.currentTimeMillis());

        ModelHealth after = new ModelHealth(dir.toString());
        assertEquals(3, after.recordTransientFailure("m"), "серия таймаутов не обнуляется деплоем");
        assertTrue(after.isBlocked("x", System.currentTimeMillis()));
        assertEquals("0", after.snapshot().get("m").outcomes);
    }
}
