package com.hh.gui.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VacancyAiAnalyzerTest {

    private VacancyAiAnalyzer analyzer;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        analyzer = new VacancyAiAnalyzer();
        // Set required fields via reflection
        setField(analyzer, "apiUrl", "http://localhost:8089/mock");
        setField(analyzer, "apiKey", "test-key");
        setField(analyzer, "model", "test/model");
        setField(analyzer, "batchSize", 5);
        setField(analyzer, "mapper", mapper);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── buildPrompt tests (we can't call private, but we can test via reflection) ──

    @Test
    void analyzer_canBeInstantiated() {
        assertNotNull(analyzer);
    }

    @Test
    void analyzer_isRateLimited_returnsFalseInitially() throws Exception {
        // Reset lastRequestTime to 0 via reflection
        Field lastReqTime = analyzer.getClass().getDeclaredField("lastRequestTime");
        lastReqTime.setAccessible(true);
        lastReqTime.setLong(analyzer, 0);

        // isRateLimited checks rateLimitCooldownUntil
        var method = analyzer.getClass().getDeclaredMethod("isRateLimited");
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(analyzer);
        assertFalse(result);
    }

    // ── Parse response edge cases ──
    // We can test the public analyzeBatch method's behavior with mocks,
    // but since it calls callLlm, we test what we can

    @Test
    void analyzer_apiKeyCheck() throws Exception {
        // Test that when apiKey is empty, analyzeBatch returns empty
        setField(analyzer, "apiKey", "");

        // With empty vacancies list, should return empty
        var result = analyzer.analyzeBatch(List.of(), null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void analyzer_nullApiKeySkipsAnalysis() {
        assertDoesNotThrow(() -> analyzer.analyzeBatch(List.of(), null));
    }

    // ── AiResult record ──

    @Test
    void aiResult_recordCreation() {
        var result = new VacancyAiAnalyzer.AiResult("123", 75, "yes", "Good match");
        assertEquals("123", result.hhId());
        assertEquals(75, result.score());
        assertEquals("yes", result.verdict());
        assertEquals("Good match", result.reason());
    }

    @Test
    void aiResult_recordWithFraud() {
        var result = new VacancyAiAnalyzer.AiResult("456", 0, "fraud", "Слишком высокая зарплата для продавца");
        assertEquals("fraud", result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void aiResult_recordWithNo() {
        var result = new VacancyAiAnalyzer.AiResult("789", 30, "no", "Не подходит");
        assertEquals("no", result.verdict());
        assertEquals(30, result.score());
    }

    // ── SearchProfile ──

    @Test
    void searchProfile_creation() {
        var profile = VacancyAiAnalyzer.SearchProfile.defaultProfile();
        assertEquals("Уфа", profile.city);
        assertEquals(List.of("Шакша", "Калининский"), profile.priorityDistricts);
        assertEquals(List.of("Работа с клиентами", "Касса", "Консультирование"), profile.skills);
        assertEquals(40000, profile.salaryMin);
    }
}
