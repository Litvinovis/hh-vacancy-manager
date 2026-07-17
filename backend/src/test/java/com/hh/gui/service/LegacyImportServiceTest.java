package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegacyImportServiceTest {

    /** Прескрин: «no» всем, чей титул содержит слово «скучн», остальным «yes». */
    private static class FakePrescreenAnalyzer extends VacancyAiAnalyzer {
        final List<Integer> batchSizes = new ArrayList<>();
        FakePrescreenAnalyzer(RuntimeConfig config) { super(config, null, null); }
        @Override
        public List<AiResult> prescreenHits(List<ScraperClient.SearchHit> hits, SearchJob job) {
            batchSizes.add(hits.size());
            return hits.stream()
                .map(h -> new AiResult(h.hhId(), 50,
                    h.title().toLowerCase().contains("скучн") ? "no" : "yes", "тест"))
                .toList();
        }
    }

    private static class FakeSaveRepo extends VacancyRepository {
        final List<Vacancy> saved = new ArrayList<>();
        FakeSaveRepo() { super(null); }
        @Override
        public Vacancy save(Vacancy v) {
            saved.add(v);
            return v;
        }
    }

    private static class FakeProfileFactory extends SearchProfileFactory {
        FakeProfileFactory() { super(null, null); }
        @Override
        public Optional<SearchJob> buildForSearchId(Long searchId) {
            SearchJob job = new SearchJob();
            job.searchId = searchId;
            job.userId = 2L;
            job.personName = "Мама";
            job.searchName = searchId == LegacyImportService.REMOTE_SEARCH_ID ? "Удалёнка по России" : "Рядом с домом";
            job.salaryMin = searchId == LegacyImportService.REMOTE_SEARCH_ID ? 40000 : 0;
            job.excludeWords = List.of("вахта");
            return Optional.of(job);
        }
    }

    /** Импортёр с подменёнными точками доступа к БД legacy. */
    private static class TestImporter extends LegacyImportService {
        List<LegacyRow> rows = new ArrayList<>();
        Set<String> existing = Set.of();
        final List<Long> stamped = new ArrayList<>();
        TestImporter(VacancyRepository repo, VacancyAiAnalyzer analyzer) {
            super(null, repo, analyzer, new FakeProfileFactory());
        }
        @Override
        protected List<LegacyRow> fetchUnmigrated(int limit, boolean onlyYes) { return rows; }
        @Override
        protected boolean hhIdExistsAnywhere(String hhId) { return existing.contains(hhId); }
        @Override
        protected void stampMigrated(List<Long> ids) { stamped.addAll(ids); }
    }

    private static LegacyImportService.LegacyRow row(long id, String hhId, String title, boolean remote, Integer salaryTo) {
        return new LegacyImportService.LegacyRow(id, hhId, title, "ООО Ромашка", null, salaryTo, "RUR",
            remote, "https://hh.ru/vacancy/" + hhId, "yes");
    }

    @Test
    void importBatch_filtersPrescreensAndStampsEverything() {
        RuntimeConfig config = new RuntimeConfig();
        FakeSaveRepo repo = new FakeSaveRepo();
        FakePrescreenAnalyzer analyzer = new FakePrescreenAnalyzer(config);
        TestImporter importer = new TestImporter(repo, analyzer);
        importer.existing = Set.of("2");
        importer.rows = List.of(
            row(1, "1", "Оператор поддержки", true, null),          // импорт (pending)
            row(2, "2", "Уже переоткрыта v2", true, null),          // alreadyPresent
            row(3, "3", "Вахта на севере", true, null),             // exclude_words
            row(4, "4", "Оператор ПК", true, 30000),                // зарплата ниже пола 40000
            row(5, "5", "Скучная рутина", true, null),              // прескрин-отказ (skipped)
            row(6, "6", "Продавец у дома", false, 30000));          // локальный поиск: пола нет → импорт

        LegacyImportService.ImportResult r = importer.importBatch(100, false);

        assertEquals(6, r.considered);
        assertEquals(1, r.alreadyPresent);
        assertEquals(2, r.excluded);
        assertEquals(1, r.prescreenRejected);
        assertEquals(2, r.imported);
        // Прескрин-отказ сохранён как skipped/no, импортированные — pending, source=hh-legacy.
        assertEquals(3, repo.saved.size());
        Vacancy imported = repo.saved.stream().filter(v -> v.getHhId().equals("1")).findFirst().orElseThrow();
        assertEquals("pending", imported.getScrapeStatus());
        assertEquals("hh-legacy", imported.getSource());
        assertEquals("Удалёнка по России", imported.getSearchName());
        assertFalse(imported.getDedupKey().isEmpty());
        Vacancy rejected = repo.saved.stream().filter(v -> v.getHhId().equals("5")).findFirst().orElseThrow();
        assertEquals("skipped", rejected.getScrapeStatus());
        assertEquals("no", rejected.getAiVerdict());
        Vacancy local = repo.saved.stream().filter(v -> v.getHhId().equals("6")).findFirst().orElseThrow();
        assertEquals("Рядом с домом", local.getSearchName());
        // ВСЕ рассмотренные строки проштампованы — batch не вернётся к ним снова.
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), importer.stamped);
    }

    @Test
    void importBatch_emptyArchive_noWork() {
        RuntimeConfig config = new RuntimeConfig();
        TestImporter importer = new TestImporter(new FakeSaveRepo(), new FakePrescreenAnalyzer(config));

        LegacyImportService.ImportResult r = importer.importBatch(100, false);

        assertEquals(0, r.considered + r.imported);
        assertTrue(importer.stamped.isEmpty());
    }
}
