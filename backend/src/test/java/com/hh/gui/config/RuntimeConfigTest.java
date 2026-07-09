package com.hh.gui.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTest {

    private RuntimeConfig config;

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig();
    }

    // ═══════ Defaults ═══════

    @Test
    void defaultValues() {
        assertEquals(30, config.getMaxPerRun());
        assertEquals(600000, config.getPipelineIntervalMs());
        assertEquals("0 0 12 * * *", config.getDailyCron());
        assertEquals(3, config.getMaxRetries());
        assertEquals(1500, config.getRequestDelayMs());
        assertEquals(12000, config.getAiRequestDelayMs());
        assertEquals(30000, config.getHttpConnectTimeoutMs());
        assertEquals(120000, config.getHttpReadTimeoutMs());
        assertEquals(50, config.getMinScore());
        assertEquals(10, config.getMaxApproved());
        assertEquals(40000, config.getSalaryMinRemote());
        assertEquals(0, config.getCooldownHours());
        assertEquals(10, config.getPipelineBatchSize());
        assertFalse(config.isNotificationsEnabled());
        assertEquals(5, config.getAiBatchSize());
        assertTrue(config.isPipelineEnabled());
    }

    // ═══════ toMap ═══════

    @Test
    void toMapContainsAllKeys() {
        Map<String, Object> m = config.toMap();
        assertTrue(m.containsKey("aiProviders"));
        assertTrue(m.containsKey("maxPerRun"));
        assertTrue(m.containsKey("pipelineIntervalMs"));
        assertTrue(m.containsKey("dailyCron"));
        assertTrue(m.containsKey("maxRetries"));
        assertTrue(m.containsKey("requestDelayMs"));
        assertTrue(m.containsKey("aiRequestDelayMs"));
        assertTrue(m.containsKey("httpConnectTimeoutMs"));
        assertTrue(m.containsKey("httpReadTimeoutMs"));
        assertTrue(m.containsKey("minScore"));
        assertTrue(m.containsKey("maxApproved"));
        assertTrue(m.containsKey("salaryMinRemote"));
        assertTrue(m.containsKey("cooldownHours"));
        assertTrue(m.containsKey("pipelineBatchSize"));
        assertTrue(m.containsKey("notificationsEnabled"));
        assertTrue(m.containsKey("aiBatchSize"));
        assertTrue(m.containsKey("pipelineEnabled"));
        assertTrue(m.containsKey("urlSearchEarlyStopThreshold"));
        assertTrue(m.containsKey("cardPrescreenBatchSize"));
        assertEquals(19, m.size());
    }

    // ═══════ Descriptors ═══════

    @Test
    void descriptorsCoversAllKeys() {
        List<RuntimeConfig.SettingDescriptor> descs = config.getDescriptors();
        assertEquals(18, descs.size());
        Set<String> keys = new HashSet<>();
        for (var d : descs) {
            assertNotNull(d.key);
            assertNotNull(d.label);
            assertNotNull(d.description);
            assertNotNull(d.type);
            keys.add(d.key);
        }
        // aiProviders is intentionally excluded from the generic descriptor-driven
        // settings form — it's a list edited through its own dedicated UI section
        // and /api/settings/providers endpoint, not a single scalar value.
        Set<String> mapKeysExcludingProviders = new HashSet<>(config.toMap().keySet());
        mapKeysExcludingProviders.remove("aiProviders");
        assertEquals(mapKeysExcludingProviders, keys);
    }

    @Test
    void descriptorLabelsAreShort() {
        for (var d : config.getDescriptors()) {
            // Label should be ≤ 4 words (Russian words separated by spaces/hyphens)
            String[] words = d.label.split("[\\s-]+");
            assertTrue(words.length <= 4,
                d.key + " label '" + d.label + "' has " + words.length + " words (max 4)");
        }
    }

    @Test
    void numberFieldsHaveMinMax() {
        for (var d : config.getDescriptors()) {
            if ("number".equals(d.type)) {
                assertNotNull(d.min, d.key + " number field missing min");
                assertNotNull(d.max, d.key + " number field missing max");
                assertTrue(d.min < d.max, d.key + " min >= max");
            }
        }
    }

    // ═══════ apply: valid updates ═══════

    @Test
    void applyValidIntValues() {
        Map<String, Object> updates = Map.of(
            "maxPerRun", 50,
            "maxRetries", 5,
            "requestDelayMs", 2000,
            "minScore", 70,
            "maxApproved", 20,
            "salaryMinRemote", 60000,
            "cooldownHours", 2
        );
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals(50, config.getMaxPerRun());
        assertEquals(5, config.getMaxRetries());
        assertEquals(2000, config.getRequestDelayMs());
        assertEquals(70, config.getMinScore());
        assertEquals(20, config.getMaxApproved());
        assertEquals(60000, config.getSalaryMinRemote());
        assertEquals(2, config.getCooldownHours());
    }

    @Test
    void applyValidBooleanValues() {
        Map<String, Object> updates = Map.of(
            "notificationsEnabled", true,
            "pipelineEnabled", false
        );
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertTrue(config.isNotificationsEnabled());
        assertFalse(config.isPipelineEnabled());
    }

    @Test
    void applyValidCronValue() {
        Map<String, Object> updates = Map.of("dailyCron", "0 0 8 * * *");
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals("0 0 8 * * *", config.getDailyCron());
    }

    @Test
    void applyStringValueAsInt() {
        Map<String, Object> updates = Map.of("maxPerRun", "100");
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals(100, config.getMaxPerRun());
    }

    @Test
    void applyBooleanAsString() {
        Map<String, Object> updates = Map.of(
            "notificationsEnabled", "true",
            "pipelineEnabled", "false"
        );
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertTrue(config.isNotificationsEnabled());
        assertFalse(config.isPipelineEnabled());
    }

    // ═══════ apply: validation errors ═══════

    @Test
    void applyRejectsNegativeInt() {
        Map<String, Object> updates = Map.of("maxPerRun", -1);
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("maxPerRun"));
    }

    @Test
    void applyRejectsZeroWhereMinIsOne() {
        Map<String, Object> updates = Map.of("maxPerRun", 0);
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("maxPerRun"));
    }

    @Test
    void applyRejectsExceedsMax() {
        Map<String, Object> updates = Map.of("maxPerRun", 999);
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("maxPerRun"));
    }

    @Test
    void applyRejectsNonNumericString() {
        Map<String, Object> updates = Map.of("maxPerRun", "abc");
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("maxPerRun"));
    }

    @Test
    void applyRejectsInvalidBoolean() {
        Map<String, Object> updates = Map.of("notificationsEnabled", "yes");
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("notificationsEnabled"));
    }

    @Test
    void applyRejectsInvalidCron() {
        Map<String, Object> updates = Map.of("dailyCron", "invalid");
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("dailyCron"));
    }

    @Test
    void applyRejectsCronWithWrongFieldCount() {
        Map<String, Object> updates = Map.of("dailyCron", "0 0 12 * *");
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("dailyCron"));
    }

    @Test
    void applyRejectsUnknownKey() {
        Map<String, Object> updates = Map.of("unknownParam", 42);
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("unknownParam"));
    }

    @Test
    void applyRejectsNonNumberTypeForInt() {
        Map<String, Object> updates = Map.of("maxPerRun", List.of(1, 2, 3));
        Map<String, String> errors = config.apply(updates);
        assertFalse(errors.isEmpty());
        assertTrue(errors.containsKey("maxPerRun"));
    }

    // ═══════ apply: partial updates ═══════

    @Test
    void applyPartialUpdateOnlyChangesSpecifiedKeys() {
        int origMaxPerRun = config.getMaxPerRun();
        Map<String, Object> updates = Map.of("maxRetries", 7);
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals(7, config.getMaxRetries());
        assertEquals(origMaxPerRun, config.getMaxPerRun()); // unchanged
    }

    @Test
    void applyMixedValidAndInvalid() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("maxRetries", 5); // valid
        updates.put("maxPerRun", -10); // invalid
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.containsKey("maxPerRun"));
        assertFalse(errors.containsKey("maxRetries"));
        assertEquals(5, config.getMaxRetries());
        // maxPerRun should remain at default since the update was rejected
        assertEquals(30, config.getMaxPerRun());
    }

    // ═══════ Boundary values ═══════

    @Test
    void applyMinBoundaryValues() {
        Map<String, Object> updates = Map.of(
            "maxPerRun", 1,
            "maxRetries", 1,
            "minScore", 0,
            "salaryMinRemote", 0
        );
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals(1, config.getMaxPerRun());
        assertEquals(0, config.getMinScore());
    }

    @Test
    void applyMaxBoundaryValues() {
        Map<String, Object> updates = Map.of(
            "maxPerRun", 500,
            "maxRetries", 10,
            "minScore", 100,
            "httpReadTimeoutMs", 300000
        );
        Map<String, String> errors = config.apply(updates);
        assertTrue(errors.isEmpty());
        assertEquals(500, config.getMaxPerRun());
        assertEquals(10, config.getMaxRetries());
    }

    // ═══════ Thread safety (volatile) ═══════

    @Test
    void concurrentUpdatesDontCrash() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                config.setMaxPerRun(30 + (i % 10));
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                config.getMaxPerRun();
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        // No exception = pass
    }
}
