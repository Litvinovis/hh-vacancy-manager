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

    // ── extractKeyInfo (description section extraction) ──

    private String extractKeyInfo(String description) throws Exception {
        var method = analyzer.getClass().getDeclaredMethod("extractKeyInfo", String.class);
        method.setAccessible(true);
        return (String) method.invoke(analyzer, description);
    }

    @Test
    void extractKeyInfo_keepsDutiesAndRequirements_dropsPerksAndCompanyIntro() throws Exception {
        String description = """
            Группа компаний работает на рынке 10 лет и является лидером отрасли.

            Обязанности:
            • Консультировать клиентов
            • Вносить данные в CRM

            Мы предлагаем:
            • Оплачиваемый отпуск
            • ДМС

            Требования:
            • Опыт работы от года
            • Грамотная речь
            """;
        String result = extractKeyInfo(description);
        assertTrue(result.contains("Консультировать клиентов"));
        assertTrue(result.contains("Опыт работы от года"));
        assertFalse(result.contains("ДМС"));
        assertFalse(result.contains("лидером отрасли"));
    }

    @Test
    void extractKeyInfo_noRecognizableSections_fallsBackToFlatTruncation() throws Exception {
        String description = "Ищем продавца в новый магазин, зарплата от 40000 рублей, звоните по телефону.";
        String result = extractKeyInfo(description);
        assertEquals(description, result);
    }

    @Test
    void extractKeyInfo_null_returnsEmpty() throws Exception {
        assertEquals("", extractKeyInfo(null));
    }

    @Test
    void extractKeyInfo_blank_returnsEmpty() throws Exception {
        assertEquals("", extractKeyInfo("   "));
    }

    @Test
    void extractKeyInfo_capsHeaders_noLeftoverArtifacts() throws Exception {
        String description = """
            О КОМПАНИИ: мы динамично развивающаяся компания.

            ОБЯЗАННОСТИ:
            • Продавать товар и консультировать покупателей по ассортименту
            • Поддерживать порядок и чистоту в торговом зале
            • Работать с кассой и вести отчётность по остаткам

            ТРЕБОВАНИЯ:
            • Опыт работы в продажах от 6 месяцев приветствуется
            • Грамотная речь и доброжелательность к покупателям
            """;
        String result = extractKeyInfo(description);
        assertTrue(result.contains("Продавать товар"));
        assertTrue(result.contains("Опыт работы в продажах"));
        // No leftover header-remainder artifacts like "И:" or ", ЕСЛИ ТЫ:" at the start
        assertFalse(result.trim().startsWith("И:"));
        assertFalse(result.contains("динамично развивающаяся"));
    }

    @Test
    void extractKeyInfo_shortStructuredSection_fallsBackToFlatTruncation() throws Exception {
        // Recognizable header present, but the kept content is too short to be useful
        // on its own (< 80 chars) — should fall back rather than send near-nothing.
        String description = "Обязанности: продавать.\n\nМы предлагаем: отличный коллектив и стабильность.";
        String result = extractKeyInfo(description);
        assertEquals(description, result);
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
