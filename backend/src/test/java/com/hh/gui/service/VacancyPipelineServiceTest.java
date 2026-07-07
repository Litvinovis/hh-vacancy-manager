package com.hh.gui.service;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the Telegram message-length chunking added after a
 * production incident: a report with many approved vacancies (maxApproved
 * allows up to 50) could exceed Telegram's 4096-char sendMessage limit, the
 * whole message would then be rejected, and — since only successfully-sent
 * vacancies get marked notified — the same (still-too-large) batch would be
 * rebuilt and rejected again on every future pipeline run, forever.
 *
 * chunkReport/formatVacancyEntry/truncate are pure string-formatting helpers
 * that never touch the injected collaborators, so this test constructs the
 * service with real RuntimeConfig but null for everything else.
 */
class VacancyPipelineServiceTest {

    private VacancyPipelineService service;

    @BeforeEach
    void setUp() {
        service = new VacancyPipelineService(null, null, null, null, null, new RuntimeConfig());
    }

    private List<List<Vacancy>> chunkReport(List<Vacancy> vacancies, String header) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("chunkReport", List.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<List<Vacancy>> result = (List<List<Vacancy>>) m.invoke(service, vacancies, header);
        return result;
    }

    private String formatVacancyEntry(Vacancy v) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("formatVacancyEntry", Vacancy.class);
        m.setAccessible(true);
        return (String) m.invoke(service, v);
    }

    private Vacancy vacancy(String title, String reason, int score) {
        Vacancy v = new Vacancy();
        v.setHhId("1");
        v.setTitle(title);
        v.setCompany("ООО Ромашка");
        v.setAiScore(score);
        v.setAiVerdict("yes");
        v.setAiReason(reason);
        v.setUrl("https://hh.ru/vacancy/1");
        return v;
    }

    @Test
    void chunkReport_smallBatch_fitsInOneChunk() throws Exception {
        List<Vacancy> vacancies = List.of(vacancy("Продавец", "Хорошо подходит", 80),
            vacancy("Кассир", "Тоже неплохо", 70));
        List<List<Vacancy>> chunks = chunkReport(vacancies, "header\n\n");
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).size());
    }

    @Test
    void chunkReport_manyVacancies_splitsAcrossMultipleMessages() throws Exception {
        // maxApproved allows up to 50 — this is exactly the scenario that broke in production.
        List<Vacancy> vacancies = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            vacancies.add(vacancy("Вакансия номер " + i + " с довольно длинным названием должности",
                "Развёрнутое обоснование подходящести этой конкретной вакансии для соискателя", 75));
        }
        List<List<Vacancy>> chunks = chunkReport(vacancies, "🔍 <b>Мама · Рядом с домом</b>\n\n");

        assertTrue(chunks.size() > 1, "50 vacancies with realistic text must not fit in a single Telegram message");

        int totalVacancies = chunks.stream().mapToInt(List::size).sum();
        assertEquals(50, totalVacancies, "every vacancy must end up in exactly one chunk, none dropped");

        for (List<Vacancy> chunk : chunks) {
            StringBuilder message = new StringBuilder("🔍 <b>Мама · Рядом с домом</b>\n\n");
            for (Vacancy v : chunk) message.append(formatVacancyEntry(v));
            assertTrue(message.length() <= 4096, "each chunked message must stay under Telegram's hard limit, was " + message.length());
        }
    }

    @Test
    void chunkReport_emptyList_returnsNoChunks() throws Exception {
        List<List<Vacancy>> chunks = chunkReport(List.of(), "header\n\n");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void formatVacancyEntry_extremelyLongTitleAndReason_getsTruncated() throws Exception {
        String hugeTitle = "А".repeat(5000);
        String hugeReason = "Б".repeat(5000);
        Vacancy v = vacancy(hugeTitle, hugeReason, 90);

        String entry = formatVacancyEntry(v);

        // A single entry must never alone be able to blow past Telegram's message limit,
        // regardless of how unusually long scraped/AI-generated text gets.
        assertTrue(entry.length() < 1000, "a single formatted entry must stay bounded, was " + entry.length());
    }
}
