package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiProviderManagerTest {

    private AiProviderManager manager;
    private RuntimeConfig config;

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig();
        config.setAiProviders(List.of(
            new AiProviderConfig("primary", "http://localhost/a", "key-a", "model-a"),
            new AiProviderConfig("fallback", "http://localhost/b", "key-b", "model-b")));
        manager = new AiProviderManager(config, new AiMetrics(new SimpleMeterRegistry(), config));
    }

    @Test
    void startsOnPrimaryProvider() {
        assertEquals("primary", manager.getCurrentProviderName());
        assertFalse(manager.isInCooldown());
    }

    @Test
    void switchToFallback_movesToSecondProvider() {
        manager.switchToFallback();
        assertEquals("fallback", manager.getCurrentProviderName());
        assertFalse(manager.isInCooldown());
    }

    @Test
    void switchToFallback_pastLastProvider_entersCooldown() {
        manager.switchToFallback(); // -> fallback
        manager.switchToFallback(); // no more providers -> cooldown
        assertTrue(manager.isInCooldown());
    }

    @Test
    void reset_returnsToFirstProvider() {
        manager.switchToFallback();
        manager.switchToFallback();
        assertTrue(manager.isInCooldown());

        manager.reset();
        assertFalse(manager.isInCooldown());
        assertEquals("primary", manager.getCurrentProviderName());
    }

    /**
     * Regression coverage for a real data race: switchToFallback() used to do a
     * non-atomic check-hasFallback / increment-currentIndex / set-state sequence
     * on fields that weren't all volatile — two threads hitting 429 at the same
     * moment (a scheduled run and a manually-triggered one overlapping, both
     * sharing this singleton bean) could interleave into a corrupted index.
     * Every mutating/reading method is now synchronized; this hammers the
     * manager from many threads and asserts the end state is always one of the
     * valid outcomes, never an exception or an out-of-range index.
     */
    @Test
    void concurrentSwitchToFallback_neverCorruptsState() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    manager.switchToFallback();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        // With only 2 providers, 20 concurrent 429s must land the manager in cooldown —
        // no exception, no index past the end of the (2-provider) list.
        assertTrue(manager.isInCooldown());
        assertTrue(manager.getCurrentIndex() >= 0 && manager.getCurrentIndex() < 2);
    }
}
