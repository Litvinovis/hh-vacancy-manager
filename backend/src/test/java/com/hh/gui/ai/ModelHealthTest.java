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

    @Test
    void orderByProduction_reliableFirst_unknownKeepOrderAtTheEnd() {
        ModelHealth h = new ModelHealth();
        // лидер ломает 40% ответов, запасная — ни одного; третья в работе ещё не была
        for (int i = 0; i < 10; i++) h.recordOutcome("leader", i % 5 >= 2);
        for (int i = 0; i < 10; i++) h.recordOutcome("backup", true);
        assertEquals(java.util.List.of("backup", "leader", "fresh"),
            h.orderByProduction(java.util.List.of("leader", "fresh", "backup")));
    }

    @Test
    void orderByProduction_smallDifferenceDecidedBySpeed_notByOneFailure() {
        ModelHealth h = new ModelHealth();
        // 10% и 15% сбоев — одна корзина: один лишний сбой не должен переставлять цепочку
        for (int i = 0; i < 20; i++) h.recordOutcome("slow", i % 10 != 0);
        for (int i = 0; i < 20; i++) h.recordOutcome("fast", i != 0 && i != 7 && i != 14);
        h.recordLatency("slow", 30_000);
        h.recordLatency("fast", 6_000);
        assertEquals(java.util.List.of("fast", "slow"), h.orderByProduction(java.util.List.of("slow", "fast")));
    }

    @Test
    void orderByProduction_withoutHistory_changesNothing() {
        ModelHealth h = new ModelHealth();
        h.recordLatency("b", 100);
        assertEquals(java.util.List.of("a", "b", "c"), h.orderByProduction(java.util.List.of("a", "b", "c")),
            "одна скорость пробы без истории работы — не основание переставлять");
    }
}
