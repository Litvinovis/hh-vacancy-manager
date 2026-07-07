package com.hh.gui.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        RuntimeConfig config = new RuntimeConfig();
        config.setAiProviders(List.of(
            new AiProviderConfig("test", "http://localhost:8089/mock", "test-key", "test/model")));
        AiProviderManager provider = new AiProviderManager(config, new AiMetrics(new SimpleMeterRegistry()));
        analyzer = new VacancyAiAnalyzer(config, provider, new AiMetrics(new SimpleMeterRegistry()));
        setField(analyzer, "batchSizeDefault", 5);
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

    // ── SearchJob ──

    @Test
    void searchJob_creation() {
        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";
        job.city = "Уфа";
        job.priorityDistricts = List.of("Шакша", "Калининский");
        job.skills = List.of("Работа с клиентами", "Касса", "Консультирование");
        job.salaryMin = 40000;
        job.schedule = "fullTime";
        job.area = 99;

        assertEquals("Уфа", job.city);
        assertEquals(List.of("Шакша", "Калининский"), job.priorityDistricts);
        assertEquals(List.of("Работа с клиентами", "Касса", "Консультирование"), job.skills);
        assertEquals(40000, job.salaryMin);
        assertFalse(job.isRemote());
    }
}
