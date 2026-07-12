package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
        service = new VacancyPipelineService(null, null, null, null, null, new RuntimeConfig(), null);
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

    // ── filterExcludedHits (URL-discovery's title-exclusion filter — mirrors filterExcluded) ──

    @SuppressWarnings("unchecked")
    private List<ScraperClient.SearchHit> filterExcludedHits(List<ScraperClient.SearchHit> hits, List<String> excludeWords) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("filterExcludedHits", List.class, List.class);
        m.setAccessible(true);
        return (List<ScraperClient.SearchHit>) m.invoke(service, hits, excludeWords);
    }

    private ScraperClient.SearchHit hit(String hhId, String title) {
        return new ScraperClient.SearchHit(hhId, title, "ООО Ромашка", null, "Уфа", "https://hh.ru/vacancy/" + hhId);
    }

    @Test
    void filterExcludedHits_noExcludeWords_keepsAll() throws Exception {
        List<ScraperClient.SearchHit> hits = List.of(hit("1", "Продавец"), hit("2", "Кассир"));
        assertEquals(2, filterExcludedHits(hits, List.of()).size());
        assertEquals(2, filterExcludedHits(hits, null).size());
    }

    @Test
    void filterExcludedHits_dropsTitlesContainingExcludedWord_caseInsensitive() throws Exception {
        List<ScraperClient.SearchHit> hits = List.of(
            hit("1", "Продавец-консультант"),
            hit("2", "МЕНЕДЖЕР по продажам (страховка)"),
            hit("3", "Кассир"));
        List<ScraperClient.SearchHit> result = filterExcludedHits(hits, List.of("страховка"));
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(h -> h.hhId().equals("2")));
    }

    // ── isBelowSalaryFloor (deterministic zero-token reject before the AI call) ──

    private boolean isBelowSalaryFloor(Vacancy v, com.hh.gui.model.SearchJob job) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("isBelowSalaryFloor", Vacancy.class, com.hh.gui.model.SearchJob.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, v, job);
    }

    private com.hh.gui.model.SearchJob jobWithSalaryMin(int salaryMin) {
        com.hh.gui.model.SearchJob job = new com.hh.gui.model.SearchJob();
        job.salaryMin = salaryMin;
        return job;
    }

    private Vacancy vacancyWithSalary(Integer from, Integer to, String currency) {
        Vacancy v = new Vacancy();
        v.setSalaryFrom(from);
        v.setSalaryTo(to);
        v.setCurrency(currency);
        return v;
    }

    @Test
    void isBelowSalaryFloor_explicitCeilingBelowFloor_rejects() throws Exception {
        assertTrue(isBelowSalaryFloor(vacancyWithSalary(20000, 35000, "RUR"), jobWithSalaryMin(40000)));
    }

    @Test
    void isBelowSalaryFloor_noExplicitCeiling_neverRejects() throws Exception {
        // "от 30000" without an upper bound might still stretch above the floor — AI decides.
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(30000, null, "RUR"), jobWithSalaryMin(40000)));
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(null, null, null), jobWithSalaryMin(40000)));
    }

    @Test
    void isBelowSalaryFloor_noConfiguredFloorOrForeignCurrency_neverRejects() throws Exception {
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(20000, 35000, "RUR"), jobWithSalaryMin(0)));
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(200, 300, "USD"), jobWithSalaryMin(40000)));
    }

    @Test
    void isBelowSalaryFloor_ceilingAtOrAboveFloor_passes() throws Exception {
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(30000, 40000, "RUR"), jobWithSalaryMin(40000)));
        assertFalse(isBelowSalaryFloor(vacancyWithSalary(30000, 60000, null), jobWithSalaryMin(40000)));
    }

    // ── htmlToText (full entity decoding) ──

    private String htmlToText(String html) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("htmlToText", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, html);
    }

    @Test
    void htmlToText_decodesNamedAndNumericEntities() throws Exception {
        String html = "<p>Зарплата &mdash; высокая, &laquo;белая&raquo;, &#8470;1 на рынке &amp; бонусы&nbsp;есть</p>";
        String text = htmlToText(html);
        assertEquals("Зарплата — высокая, «белая», №1 на рынке & бонусы есть", text);
    }

    @Test
    void htmlToText_preservesBulletsAndBreaks() throws Exception {
        String html = "<p>Обязанности:</p><ul><li>первое</li><li>второе</li></ul>";
        String text = htmlToText(html);
        assertTrue(text.contains("• первое"));
        assertTrue(text.contains("• второе"));
    }

    // ── isSiteWideFailure (scrape-cooldown trigger classification) ──

    private boolean isSiteWideFailure(String reason) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("isSiteWideFailure", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, reason);
    }

    @Test
    void isSiteWideFailure_perVacancyReasons_doNotFreezeScraping() throws Exception {
        // Регрессия: три подряд hh-шных 403 на скрытых вакансиях замораживали
        // ВЕСЬ скрейпинг на 30+ минут, хотя сессия работала (соседние вакансии
        // в том же прогоне скрейпились успешно).
        assertFalse(isSiteWideFailure("http_403"));
        assertFalse(isSiteWideFailure("not_found"));
        assertFalse(isSiteWideFailure("no_job_posting_data"));
    }

    @Test
    void isSiteWideFailure_blockedSidecarDownOrOtherHttp_freezeScraping() throws Exception {
        assertTrue(isSiteWideFailure("blocked")); // DDoS-Guard challenge, распознан сайдкаром
        assertTrue(isSiteWideFailure("client_error: connect refused"));
        assertTrue(isSiteWideFailure("http_429"));
        assertTrue(isSiteWideFailure("http_500"));
    }

    // ── discoverFromUrl: ранний стоп не должен терять уже собранные новые хиты ──

    private static ScraperClient.SearchHit hit(String hhId) {
        return new ScraperClient.SearchHit(hhId, "Вакансия " + hhId, "ООО Ромашка", null, null,
            "https://hh.ru/vacancy/" + hhId);
    }

    private static SearchJob urlJob() {
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Интересная удалёнка";
        job.isGlobal = true;
        return job;
    }

    /** Отдаёт заранее заданные страницы по номеру вызова (0, 1, 2...) и считает обращения. */
    private static class FakeScraper extends ScraperClient {
        final List<SearchPageResult> pages;
        int calls = 0;
        FakeScraper(RuntimeConfig config, SearchPageResult... pages) {
            super(config);
            this.pages = List.of(pages);
        }
        @Override
        public SearchPageResult searchByUrl(String url, int pageNum) {
            SearchPageResult result = pages.get(calls);
            calls++;
            return result;
        }
    }

    /** Известность по фиксированному набору hh_id; сохранённое копится в saved. */
    private static class FakeRepo extends VacancyRepository {
        final Set<String> known;
        final List<Vacancy> saved = new ArrayList<>();
        FakeRepo(Set<String> known) {
            super(null);
            this.known = known;
        }
        @Override
        public Set<String> findExistingHhIds(Collection<String> hhIds, String person, String searchName) {
            Set<String> result = new java.util.HashSet<>(hhIds);
            result.retainAll(known);
            return result;
        }
        @Override
        public Vacancy save(Vacancy v) {
            saved.add(v);
            return v;
        }
    }

    /** Прескрин «всё подходит»: пустой список вердиктов = ни одного отсева. */
    private static class FakeAnalyzer extends VacancyAiAnalyzer {
        FakeAnalyzer(RuntimeConfig config) {
            super(config, null, null);
        }
        @Override
        public List<AiResult> prescreenHits(List<ScraperClient.SearchHit> hits, SearchJob job) {
            return List.of();
        }
    }

    @Test
    void discoverFromUrl_walksAllRequestedPages_regardlessOfKnownRatio() {
        // Регрессия (версия 1, PR #45): break из середины обхода страницы выбрасывал
        // уже собранные newHits при трёх известных подряд в конце страницы.
        // Регрессия (версия 2, 2026-07-12): заменили это на стоп по доле известных
        // на всей странице — но живые данные показали, что и это не подходит для
        // этой выдачи: переопубликованные клоны перемешивают старое и новое не
        // только внутри страницы, но и между страницами, так что одна "насыщенная"
        // страница (например, 100% известных) может стоять прямо перед страницей,
        // где полно нового. Итог: пагинация всегда проходит все запрошенные страницы
        // (до MAX_URL_SEARCH_PAGES), без какой-либо остановки по доле известных —
        // каждая новая вакансия на каждой просмотренной странице сохраняется.
        RuntimeConfig config = new RuntimeConfig();
        List<ScraperClient.SearchHit> page0 = List.of(
            hit("101"), hit("901"), hit("902"), hit("903")); // 1 новая среди известных — не должно ничего останавливать
        List<ScraperClient.SearchHit> page1 = List.of(
            hit("904"), hit("905"), hit("906"), hit("907")); // страница целиком известна — раньше остановило бы пагинацию
        List<ScraperClient.SearchHit> page2 = List.of(hit("102")); // но за ней всё равно есть новое
        FakeScraper scraper = new FakeScraper(config,
            new ScraperClient.SearchPageResult(true, null, page0, null),
            new ScraperClient.SearchPageResult(true, null, page1, null),
            new ScraperClient.SearchPageResult(true, null, page2, null));
        FakeRepo repo = new FakeRepo(Set.of("901", "902", "903", "904", "905", "906", "907"));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, new FakeAnalyzer(config), repo, null, config, null);

        int saved = svc.discoverFromUrl(urlJob(), "https://hh.ru/search/vacancy?text=x", 3);

        assertEquals(2, saved, "новые вакансии и до, и после полностью известной страницы должны сохраниться");
        assertEquals(List.of("101", "102"), repo.saved.stream().map(Vacancy::getHhId).toList());
        assertEquals(3, scraper.calls, "должны быть запрошены все 3 страницы, включая ту, что идёт после 100%-известной");
    }
}
