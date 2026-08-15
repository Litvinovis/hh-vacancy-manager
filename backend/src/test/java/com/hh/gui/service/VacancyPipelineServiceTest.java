package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.client.TelegramClient;
import com.hh.gui.config.FeatureFlags;
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
        service = new VacancyPipelineService(null, null, null, null, null, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);
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
        return new ScraperClient.SearchHit(hhId, title, "ООО Ромашка", null, "Уфа", null, "https://hh.ru/vacancy/" + hhId);
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

    @Test
    void filterExcludedHits_dropsByEmployerName_evenWithGenericTitle() throws Exception {
        // Живой пример: риелторские агентства нанимают под нейтральным заголовком
        // ("Менеджер / Помощник руководителя") — само слово встречается только в
        // названии работодателя.
        List<ScraperClient.SearchHit> hits = List.of(
            new ScraperClient.SearchHit("1", "Менеджер / Помощник руководителя", "Агентство Недвижимости Инфинити", null, "Уфа", null, "https://hh.ru/vacancy/1"),
            hit("2", "Кассир"));
        List<ScraperClient.SearchHit> result = filterExcludedHits(hits, List.of("риэлтор", "риелтор", "агентство недвижимости"));
        assertEquals(1, result.size());
        assertEquals("2", result.get(0).hhId());
    }

    // ── filterExcluded (Vacancy-версия того же фильтра, используется после скрейпинга) ──

    private List<Vacancy> filterExcluded(List<Vacancy> vacancies, List<String> excludeWords) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("filterExcluded", List.class, List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Vacancy> result = (List<Vacancy>) m.invoke(service, vacancies, excludeWords);
        return result;
    }

    @Test
    void filterExcluded_dropsByEmployerName_evenWithGenericTitle() throws Exception {
        Vacancy realtor = vacancy("Менеджер / Помощник руководителя", "", 0);
        realtor.setCompany("Агентство Недвижимости Инфинити");
        Vacancy other = vacancy("Кассир", "", 0);
        other.setCompany("ООО Ромашка");

        List<Vacancy> result = filterExcluded(List.of(realtor, other), List.of("риэлтор", "риелтор", "агентство недвижимости"));

        assertEquals(1, result.size());
        assertEquals("Кассир", result.get(0).getTitle());
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
        return new ScraperClient.SearchHit(hhId, "Вакансия " + hhId, "ООО Ромашка", null, null, null,
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
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        int saved = svc.discoverFromUrl(urlJob(), "https://hh.ru/search/vacancy?text=x", 3);

        assertEquals(2, saved, "новые вакансии и до, и после полностью известной страницы должны сохраниться");
        assertEquals(List.of("101", "102"), repo.saved.stream().map(Vacancy::getHhId).toList());
        assertEquals(3, scraper.calls, "должны быть запрошены все 3 страницы, включая ту, что идёт после 100%-известной");
    }

    // ── analyzeBatchWithDedup: схлопывание клонов по dedup_key внутри одного пакета ──

    /** Репозиторий для анализа: без уже готовых вердиктов, копит вызовы updateAiResult. */
    private static class FakeAnalyzeRepo extends VacancyRepository {
        final List<String> aiResultsFor = new ArrayList<>();
        FakeAnalyzeRepo() { super(null); }
        @Override
        public void updateCriteriaHashBatch(List<Long> ids, String criteriaHash) {}
        @Override
        public java.util.Optional<Vacancy> findAnalyzedByHhIdAndCriteriaHash(String hhId, String criteriaHash) {
            return java.util.Optional.empty();
        }
        @Override
        public java.util.Optional<Vacancy> findAnalyzedByDedupKeyAndCriteriaHash(String dedupKey, String criteriaHash) {
            return java.util.Optional.empty();
        }
        @Override
        public void updateAiResult(String hhId, String person, String searchName, int score, String verdict, String reason) {
            aiResultsFor.add(hhId);
        }
        @Override
        public void updateAiResult(String hhId, String person, String searchName, int score, String verdict, String reason,
                                    String noveltyColor, String noveltyNote) {
            aiResultsFor.add(hhId);
        }
        @Override
        public void updateAiResult(String hhId, String person, String searchName, int score, String verdict, String reason,
                                    String noveltyColor, String noveltyNote,
                                    Integer aiSalaryFrom, Integer aiSalaryTo, String aiCurrency, String aiCompany) {
            aiResultsFor.add(hhId);
        }
        @Override
        public void incrementAiAttemptsBatch(List<Long> ids) {}
        @Override
        public int markAiExhausted(String person, String searchName, int maxAttempts) { return 0; }
    }

    /** Отвечает вердиктом на всё, что прислали, и запоминает размер пакета, ушедшего в LLM. */
    private static class FakeBatchAnalyzer extends VacancyAiAnalyzer {
        final List<Integer> llmBatchSizes = new ArrayList<>();
        FakeBatchAnalyzer(RuntimeConfig config) { super(config, null, null); }
        @Override
        public String computeCriteriaHash(SearchJob job) { return "hash"; }
        @Override
        public List<AiResult> analyzeBatch(List<Vacancy> vacancies, SearchJob job) {
            llmBatchSizes.add(vacancies.size());
            return vacancies.stream().map(v -> new AiResult(v.getHhId(), 70, "yes", "ок", "", "")).toList();
        }
    }

    private static Vacancy pendingVacancy(String hhId, String dedupKey) {
        Vacancy v = new Vacancy();
        v.setId(Long.parseLong(hhId));
        v.setHhId(hhId);
        v.setTitle("Вакансия " + hhId);
        v.setDedupKey(dedupKey);
        return v;
    }

    @Test
    void analyzeBatchWithDedup_cityClonesInSameBatch_onlyRepresentativeGoesToLlm() throws Exception {
        // Живой сценарий: пачка pending содержит 3 клона одной вакансии (один dedup_key,
        // разные hh_id по городам) и 1 без ключа — в LLM должны уйти 2, вердикт — на все 4.
        RuntimeConfig config = new RuntimeConfig();
        FakeAnalyzeRepo repo = new FakeAnalyzeRepo();
        FakeBatchAnalyzer analyzer = new FakeBatchAnalyzer(config);
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, analyzer, repo, null, config,
            new com.hh.gui.ai.AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), config),
            new FeatureFlags(), null, null);

        List<Vacancy> batch = List.of(
            pendingVacancy("1", "поддержка|т банк"),
            pendingVacancy("2", "поддержка|т банк"),
            pendingVacancy("3", "поддержка|т банк"),
            pendingVacancy("4", null));

        Method m = VacancyPipelineService.class.getDeclaredMethod("analyzeBatchWithDedup", List.class, SearchJob.class);
        m.setAccessible(true);
        int analyzed = (int) m.invoke(svc, batch, urlJob());

        assertEquals(List.of(2), analyzer.llmBatchSizes, "в LLM должны уйти представитель клонов и вакансия без ключа");
        assertEquals(4, analyzed, "все 4 вакансии должны считаться обработанными");
        assertEquals(Set.of("1", "2", "3", "4"), Set.copyOf(repo.aiResultsFor),
            "вердикт должен быть записан каждой вакансии, включая клонов");
    }

    // ── checkVacancyFreshness: актуализация одобренных вакансий ──

    private static class FakeFreshnessRepo extends VacancyRepository {
        List<Vacancy> due = new ArrayList<>();
        int backlog = 0;
        final List<Long> checked = new ArrayList<>();
        final List<Long> closed = new ArrayList<>();
        FakeFreshnessRepo() { super(null); }
        @Override
        public int countUnscrapedNew() { return backlog; }
        @Override
        public List<Vacancy> findDueFreshnessCheck(int days, int limit) { return due; }
        @Override
        public void markFreshnessChecked(Long id) { checked.add(id); }
        @Override
        public void markClosed(Long id) { closed.add(id); }
        @Override
        public void updateScraped(Vacancy v) {}
    }

    private static class FreshnessScraper extends ScraperClient {
        final java.util.Map<String, ScrapeResult> byId = new java.util.HashMap<>();
        int calls = 0;
        FreshnessScraper(RuntimeConfig config) { super(config); }
        @Override
        public ScrapeResult scrape(String hhId) {
            calls++;
            return byId.get(hhId);
        }
    }

    private static ScraperClient.ScrapeResult failResult(String reason) {
        return new ScraperClient.ScrapeResult(false, reason, null, null, null, null, null, null, null,
            null, null, null, null, null, List.of(), false, null, null);
    }

    // ── scrapePending: http_403-burst backstop должен игнорировать legacy-строки ──

    private static class FakePendingRepo extends VacancyRepository {
        List<Vacancy> pending = new ArrayList<>();
        int updateScrapedCalls = 0;
        FakePendingRepo() { super(null); }
        @Override
        public List<Vacancy> findScrapePending(String person, String searchName, int limit, int maxAttempts) { return pending; }
        @Override
        public java.util.Optional<Vacancy> findFirstScrapedByHhId(String hhId) { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional<Vacancy> findFirstScrapedByDedupKey(String dedupKey) { return java.util.Optional.empty(); }
        @Override
        public void updateScraped(Vacancy v) { updateScrapedCalls++; }
        @Override
        public void incrementScrapeAttempts(Long id) {}
    }

    private static Vacancy scrapeStub(String hhId, String source) {
        Vacancy v = new Vacancy();
        v.setId(Long.parseLong(hhId));
        v.setHhId(hhId);
        v.setSource(source);
        v.setDedupKey("");
        return v;
    }

    private int scrapePending(VacancyPipelineService svc, SearchJob job) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("scrapePending", SearchJob.class);
        m.setAccessible(true);
        return (int) m.invoke(svc, job);
    }

    @Test
    void scrapePending_legacy403Cluster_doesNotTripBurstCooldown() throws Exception {
        // Регрессия (инцидент 2026-07-20/21): переработка архива v1 регулярно
        // приносит пачки месяцами скрытых вакансий (http_403 на каждую) — раньше
        // это 7 раз за одно утро замораживало ВЕСЬ скрейпинг на 30-120 минут,
        // хотя ни одна свежая вакансия не блокировалась.
        RuntimeConfig config = new RuntimeConfig();
        FakePendingRepo repo = new FakePendingRepo();
        for (int i = 0; i < 10; i++) {
            repo.pending.add(scrapeStub("100" + i, "hh-legacy"));
        }
        FreshnessScraper scraper = new FreshnessScraper(config);
        repo.pending.forEach(v -> scraper.byId.put(v.getHhId(), failResult("http_403")));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        int count = scrapePending(svc, urlJob());

        assertEquals(10, count, "все 10 legacy-403 должны быть обработаны без остановки по бёрсту");
        assertFalse(svc.isScrapeCoolingDown(), "legacy-403 не должны замораживать скрейпинг");
    }

    @Test
    void scrapePending_fresh403Cluster_stillTripsBurstCooldown() throws Exception {
        // Свежие (не legacy) 403 — по-прежнему полноценный сигнал возможного
        // рейт-лимита, защита должна сработать как раньше.
        RuntimeConfig config = new RuntimeConfig();
        FakePendingRepo repo = new FakePendingRepo();
        for (int i = 0; i < 10; i++) {
            repo.pending.add(scrapeStub("200" + i, "hh"));
        }
        FreshnessScraper scraper = new FreshnessScraper(config);
        repo.pending.forEach(v -> scraper.byId.put(v.getHhId(), failResult("http_403")));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        scrapePending(svc, urlJob());

        assertTrue(svc.isScrapeCoolingDown(), "бёрст свежих 403 должен по-прежнему замораживать скрейпинг");
    }

    private void enterScrapeCooldown(VacancyPipelineService svc) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("enterScrapeCooldown");
        m.setAccessible(true);
        m.invoke(svc);
    }

    private int scrapeCooldownStrikes(VacancyPipelineService svc) throws Exception {
        java.lang.reflect.Field f = VacancyPipelineService.class.getDeclaredField("scrapeCooldownStrikes");
        f.setAccessible(true);
        return f.getInt(svc);
    }

    @Test
    void enterScrapeCooldown_calledWhileAlreadyCoolingDown_doesNotDoubleStrike() throws Exception {
        // Регрессия: разные поиски выполняются параллельно (см. differentJobs_runConcurrently
        // ниже), так что один и тот же реальный блок hh.ru независимые прогоны могли
        // обнаружить порознь в течение одной секунды — каждый вызывал enterScrapeCooldown(),
        // и счётчик страйков прыгал 1→2→3 сразу, разгоняя заморозку до нескольких часов
        // за одно событие вместо честной эскалации после действительно повторной блокировки.
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, null, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        enterScrapeCooldown(svc);
        enterScrapeCooldown(svc);
        enterScrapeCooldown(svc);

        assertEquals(1, scrapeCooldownStrikes(svc),
            "повторные срабатывания, пока заморозка уже активна, не должны разгонять эскалацию");
    }

    @Test
    void checkVacancyFreshness_aliveArchivedAndInconclusive_handledDistinctly() {
        RuntimeConfig config = new RuntimeConfig();
        FakeFreshnessRepo repo = new FakeFreshnessRepo();
        repo.due = List.of(pendingVacancy("11", null), pendingVacancy("12", null), pendingVacancy("13", null));
        FreshnessScraper scraper = new FreshnessScraper(config);
        scraper.byId.put("11", new ScraperClient.ScrapeResult(true, null, "Живая", "Ромашка", "<p>desc</p>",
            null, null, null, null, "Уфа", null, null, null, null, List.of(), false, null, null));
        scraper.byId.put("12", failResult("archived"));
        scraper.byId.put("13", failResult("http_403"));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        VacancyPipelineService.FreshnessResult r = svc.checkVacancyFreshness(5);

        assertEquals(1, r.alive);
        assertEquals(1, r.closed);
        assertEquals(1, r.inconclusive);
        assertEquals(List.of(12L), repo.closed, "архивная помечена закрытой");
        // Живая и неубедительная (403) — обе получают штамп проверки, чтобы ждать полный интервал.
        assertEquals(List.of(11L, 13L), repo.checked);
    }

    @Test
    void checkVacancyFreshness_largeNewContentBacklog_yields() {
        RuntimeConfig config = new RuntimeConfig();
        FakeFreshnessRepo repo = new FakeFreshnessRepo();
        repo.backlog = VacancyPipelineService.FRESHNESS_MAX_SCRAPE_BACKLOG + 1;
        repo.due = List.of(pendingVacancy("11", null));
        FreshnessScraper scraper = new FreshnessScraper(config);
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        VacancyPipelineService.FreshnessResult r = svc.checkVacancyFreshness(5);

        assertEquals(0, r.alive + r.closed + r.inconclusive);
        assertEquals(0, scraper.calls, "большая очередь новых вакансий — актуализация уступает");
    }

    @Test
    void checkVacancyFreshness_smallResidualBacklog_stillRuns() {
        // Регрессия: «только при строго пустой очереди» на живых данных означало
        // «никогда» — очередь не бывала нулевой сутками, и актуализация голодала.
        RuntimeConfig config = new RuntimeConfig();
        FakeFreshnessRepo repo = new FakeFreshnessRepo();
        repo.backlog = VacancyPipelineService.FRESHNESS_MAX_SCRAPE_BACKLOG; // мелкий остаток — не блокирует
        repo.due = List.of(pendingVacancy("11", null));
        FreshnessScraper scraper = new FreshnessScraper(config);
        scraper.byId.put("11", failResult("archived"));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, scraper, null, new FakeAnalyzer(config), repo, null, config, null, new FeatureFlags(), null, null);

        VacancyPipelineService.FreshnessResult r = svc.checkVacancyFreshness(5);

        assertEquals(1, r.closed, "при мелком остатке очереди актуализация должна работать");
    }

    private void sendReport(VacancyPipelineService svc, List<Vacancy> approved, SearchJob job) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("sendReport", List.class, SearchJob.class);
        m.setAccessible(true);
        m.invoke(svc, approved, job);
    }

    @Test
    void sendReport_deliveryDisabled_skipsDedupEntirely() throws Exception {
        // Регрессия: дедуп выполнялся ДО проверки «доставка включена». Одобренные строки
        // не помечаются notified, пока назначение выключено, поэтому findUnnotifiedApproved
        // возвращал те же строки каждый тик — и каждые ~10 минут заново гонялись запросы в
        // БД по работодателю и сравнение полных описаний по бэклогу в ~20k строк впустую.
        // Репозиторий здесь null: если дедуп всё же запустится, тест упадёт с NPE.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(false);
        config.setChannelNotificationsEnabled(false);
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, null, null, config, null, new FeatureFlags(), null, null);

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        Vacancy v = vacancy("Продавец", "Подходит", 80);
        v.setDescription("Обязанности: продавать\nТребования: опыт");

        assertDoesNotThrow(() -> sendReport(svc, List.of(v), job),
            "при выключенной доставке дедуп не должен выполняться вовсе");
    }

    @Test
    void sendReport_deliveryEnabled_stillDedupes() throws Exception {
        // Обратная сторона: когда доставка включена, дедуп обязан отработать как раньше.
        // Тот же null-репозиторий — теперь NPE ОЖИДАЕМ, он доказывает, что дедуп дошёл
        // до findNotifiedByEmployer.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(true);
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, null, null, config, null, new FeatureFlags(), null, null);

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        Vacancy v = vacancy("Продавец", "Подходит", 80);
        v.setDescription("Обязанности: продавать\nТребования: опыт");

        assertThrows(Exception.class, () -> sendReport(svc, List.of(v), job),
            "при включённой доставке дедуп должен обращаться к репозиторию");
    }

    /** Репозиторий для теста similarity-дедупа: отдаёт подготовленный список уже
     * уведомлённых вакансий по работодателю, копит id, переданные в markNotified. */
    private static class FakeSimilarityRepo extends VacancyRepository {
        List<Vacancy> alreadyNotified = List.of();
        List<Vacancy> unresolvedEmployerPool = List.of();
        final List<Long> markedNotified = new ArrayList<>();
        FakeSimilarityRepo() { super(null); }
        @Override
        public List<Vacancy> findNotifiedByEmployer(String person, String searchName, String employerName) {
            return alreadyNotified;
        }
        @Override
        public List<Vacancy> findWithUnresolvedEmployer(String person, String searchName) {
            return unresolvedEmployerPool;
        }
        @Override
        public void markNotified(List<Long> ids) {
            markedNotified.addAll(ids);
        }
    }

    private static class RecordingNotifier extends TelegramNotifier {
        final List<String> sent = new ArrayList<>();
        @Override
        public boolean send(String message, String targetChatId) {
            sent.add(message);
            return true;
        }
    }

    @Test
    void sendReport_similarityDuplicate_resolvedSoItStopsComingBack() throws Exception {
        // Регрессия: dedupeByKey-клоны самостоятельно разрешаются через SQL-guard
        // findUnnotifiedApproved, как только их двойник помечен notified. Но у клона по
        // схожести описания dedup_key другой — этот guard его не ловит, и раньше он
        // приходил из findUnnotifiedApproved заново на каждом тике пайплайна, снова
        // проигрывал то же сравнение с БД и снова отбрасывался — навсегда, впустую.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, repo, new RecordingNotifier(), config, null, new FeatureFlags(), null, null);

        Vacancy alreadySent = vacancy("Продавец", "Подходит", 80);
        alreadySent.setDescription("Обязанности: продавать\nТребования: опыт");
        repo.alreadyNotified = List.of(alreadySent);

        Vacancy candidate = vacancy("Продавец-консультант", "Подходит", 75);
        candidate.setId(50L);
        candidate.setDescription("Обязанности: продавать\nТребования: опыт"); // идентичное описание — похожесть 1.0

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        sendReport(svc, List.of(candidate), job);

        assertEquals(List.of(50L), repo.markedNotified,
            "клон по схожести должен быть помечен notified, иначе findUnnotifiedApproved будет возвращать его вечно");
    }

    @Test
    void sendReport_crossChannelDuplicateWithUnresolvedEmployer_isDropped() throws Exception {
        // Живой пример: "Удаленный оператор ЕГАИС / Товаровед Saby" repostнута на двух
        // разных каналах, ни один не назвал реального работодателя (company="@channel1"/
        // "@channel2") — employerKey для них разный, per-employer сравнение их не видит
        // вместе. findWithUnresolvedEmployer расширяет пул сравнения на все каналы сразу.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingNotifier notifier = new RecordingNotifier();
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, repo, notifier, config, null, new FeatureFlags(), null, null);

        Vacancy alreadyQueuedOnOtherChannel = vacancy("Оператор ЕГАИС", "Подходит", 80);
        alreadyQueuedOnOtherChannel.setCompany("@onlinevakansii");
        alreadyQueuedOnOtherChannel.setDescription("Обязанности: работа с ЕГАИС\nТребования: опыт");
        repo.unresolvedEmployerPool = List.of(alreadyQueuedOnOtherChannel);

        Vacancy candidate = vacancy("Оператор ЕГАИС", "Подходит", 75);
        candidate.setId(60L);
        candidate.setCompany("@rabota_onlaynr"); // другой канал, тоже не назвавший работодателя
        candidate.setDescription("Обязанности: работа с ЕГАИС\nТребования: опыт"); // идентичное описание

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        sendReport(svc, List.of(candidate), job);

        // markNotified тоже вызывается при обычной успешной отправке — реальный сигнал
        // "отброшен как дубль, а не отправлен" это отсутствие вызова notifier.send.
        assertTrue(notifier.sent.isEmpty(), "дубль с другого канала не должен быть реально отправлен");
        assertEquals(List.of(60L), repo.markedNotified,
            "дубль с другого канала при нераспознанном работодателе на обоих должен быть отброшен как повтор");
    }

    @Test
    void sendReport_sameTitleDifferentRealEmployers_bothKept() throws Exception {
        // Контрольный случай: два РАЗНЫХ канала, но у ОБОИХ уже известен настоящий
        // (не заглушка) работодатель — межканальная проверка не должна вмешиваться,
        // остаётся обычный per-employer дедуп, который их не спутает.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingNotifier notifier = new RecordingNotifier();
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, repo, notifier, config, null, new FeatureFlags(), null, null);

        Vacancy candidate = vacancy("Менеджер по продажам", "Подходит", 75);
        candidate.setId(61L);
        candidate.setCompany("Реальная Компания ООО"); // не заглушка
        candidate.setDescription("Обязанности: продавать\nТребования: опыт");

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        sendReport(svc, List.of(candidate), job);

        assertEquals(1, notifier.sent.size(), "с реальным работодателем не должен сработать межканальный дедуп-фолбэк — вакансия должна уйти в отчёт");
    }

    // ── Взаимное исключение прогонов одного поиска ──

    /** Считает, сколько прогонов одного поиска выполнялось одновременно. */
    private static class ConcurrencyProbe extends VacancyPipelineService {
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger bodyRuns = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch bodyEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        ConcurrencyProbe(RuntimeConfig config) {
            super(null, null, null, null, null, null, config, null, new FeatureFlags(), null, null);
        }

        @Override
        protected PipelineResult runFullPipelineLocked(SearchJob job, boolean deferSmallAiBatches) {
            bodyRuns.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            bodyEntered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new PipelineResult();
        }
    }

    @Test
    void sameJob_cannotRunTwiceConcurrently() throws Exception {
        // Регрессия: PipelineJobRunner разводит только ручные прогоны между собой, а
        // планировщик — независимый вызывающий и пересекается с ними свободно. Два
        // прогона одного поиска выбирали одни и те же строки notified=0 (между SELECT и
        // markNotified лежит отправка в Telegram, это секунды) и публиковали их дважды —
        // дубли, которые дедуп поймать не может, потому что пачки независимые.
        ConcurrencyProbe svc = new ConcurrencyProbe(new RuntimeConfig());
        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        Thread first = new Thread(() -> svc.runFullPipeline(job, false));
        first.start();
        assertTrue(svc.bodyEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "первый прогон должен стартовать");

        // Второй заходит, пока первый ещё внутри — должен вернуться сразу, не выполнив тело.
        VacancyPipelineService.PipelineResult skipped = svc.runFullPipeline(job, false);

        svc.release.countDown();
        first.join(5000);

        assertEquals(1, svc.bodyRuns.get(), "тело пайплайна должно выполниться ровно один раз");
        assertEquals(1, svc.maxInFlight.get(), "два прогона одного поиска не должны пересекаться");
        assertEquals(0, skipped.approved, "пропущенный запуск возвращает пустой результат");
    }

    @Test
    void differentJobs_runConcurrently() throws Exception {
        // Блокировка обязана быть per-job: разные поиски по-прежнему идут параллельно.
        ConcurrencyProbe svc = new ConcurrencyProbe(new RuntimeConfig());
        SearchJob a = new SearchJob();
        a.personName = "Мама";
        a.searchName = "Рядом с домом";
        SearchJob b = new SearchJob();
        b.personName = "Мама";
        b.searchName = "Удалёнка по России";

        Thread t1 = new Thread(() -> svc.runFullPipeline(a, false));
        Thread t2 = new Thread(() -> svc.runFullPipeline(b, false));
        t1.start();
        assertTrue(svc.bodyEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        t2.start();
        Thread.sleep(200);

        svc.release.countDown();
        t1.join(5000);
        t2.join(5000);

        assertEquals(2, svc.bodyRuns.get(), "разные поиски должны выполняться оба");
        assertEquals(2, svc.maxInFlight.get(), "разные поиски должны идти параллельно");
    }

    // ── discoverFromTelegram: Path A (ссылка на hh.ru) vs Path B (только текст поста) ──

    private static SearchJob tgJob() {
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Без техстека";
        job.isGlobal = true;
        return job;
    }

    private static TelegramClient.TelegramMessage tgMsg(String id, String text) {
        return new TelegramClient.TelegramMessage(id, text, "2026-08-15T09:00:00.000Z",
            "https://t.me/testchan/" + id, "testchan", "telegram");
    }

    private static class FakeTelegramClient extends TelegramClient {
        final java.util.Map<String, ChannelResult> byChannel;
        FakeTelegramClient(java.util.Map<String, ChannelResult> byChannel) { this.byChannel = byChannel; }
        @Override
        public ChannelResult fetchChannel(String username, int limit) {
            return byChannel.getOrDefault(username, new ChannelResult(true, null, List.of()));
        }
    }

    private static class FakeTgRepo extends VacancyRepository {
        final Set<String> known;
        final List<Vacancy> saved = new ArrayList<>();
        FakeTgRepo(Set<String> known) {
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

    @Test
    void discoverFromTelegram_hhLinkInPost_goesThroughNormalHhPipelineAsPathA() {
        // Пост со ссылкой на hh.ru — это Path A: сохраняем обычный scrape-pending стаб
        // по hh_id из ссылки, а не текст поста как готовое описание.
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Менеджер по продажам\nПодробности: https://ufa.hh.ru/vacancy/123456789")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        int saved = svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, saved);
        Vacancy v = repo.saved.get(0);
        assertEquals("123456789", v.getHhId(), "hh_id должен быть извлечён из ссылки в посте, а не из id сообщения");
        assertEquals("hh", v.getSource());
        assertEquals("pending", v.getScrapeStatus(), "Path A должен идти через обычный скрейпинг, не готовую вакансию");
    }

    @Test
    void discoverFromTelegram_noFirstPartyLink_savedAsOriginalTelegramContentPathB() {
        // Пост без ссылки на биржу — Path B: текст поста сам по себе источник, скрейпинг
        // не нужен (scrape_status сразу 'ok'), готов к AI-анализу.
        String text = "Оператор чата, удалённо\nЗарплата от 40000\nОбращаться в лс";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_42", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        int saved = svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, saved);
        Vacancy v = repo.saved.get(0);
        assertEquals("tg_testchan_42", v.getHhId());
        assertEquals("telegram", v.getSource());
        assertEquals("ok", v.getScrapeStatus(), "Path B уже содержит весь текст — скрейпить нечего");
        assertEquals("pending", v.getAiVerdict());
        assertEquals(text, v.getDescription(), "полный текст поста должен уйти в AI-анализ как описание");
        assertFalse(v.getDedupKey().isEmpty(), "без явного работодателя dedup_key всё равно должен строиться (см. extractTgEmployer)");
    }

    @Test
    void discoverFromTelegram_hashtagLeadLine_titleIsTheRoleNameNotTheHashtags() {
        // Живой пример: frilanser_vacansii и похожие каналы открывают каждый пост строкой
        // хэштегов (#вакансия #smm #удаленно) — без пропуска этой строки заголовком
        // вакансии становился набор хэштегов, а не должность.
        String text = "​#вакансия #smm #онлайншкола #удаленно\n\n SMM-специалист в онлайн-школу вязания Sviteroff\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_99", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, repo.saved.size());
        assertEquals("SMM-специалист в онлайн-школу вязания Sviteroff", repo.saved.get(0).getTitle());
    }

    @Test
    void discoverFromTelegram_markdownHeadingTitle_stripsHeadingAndVacancyPrefix() {
        // Живой пример: некоторые каналы форматируют первую строку как markdown-заголовок
        // с общим префиксом ("### Вакансия: Асессор") — заголовком должна остаться
        // только сама должность, без "###" и "Вакансия:".
        String text = "### Вакансия: Асессор\n\nКаждый день миллионы людей смотрят...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_100", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, repo.saved.size());
        assertEquals("Асессор", repo.saved.get(0).getTitle());
    }

    @Test
    void discoverFromTelegram_titleNamesEmployer_extractsItInsteadOfChannelFallback() {
        // company идёт прямо в опубликованный пост (formatVacancyEntry) — "@channel"
        // вместо реального работодателя видит каждый читатель канала. Заголовок вида
        // "Роль в/для КомпанияName" — самый частый способ узнать работодателя без
        // явного "Компания:" в тексте.
        String text = "Брендинг-дизайнер в Emerging Travel Group\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_55", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("Emerging Travel Group", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_titleHasNoNamedEmployer_fallsBackToChannel() {
        // "для международных проектов" — lowercase after "для", не похоже на название
        // компании: должен остаться безопасный fallback на канал, а не мусор вроде
        // "международных проектов" в поле работодателя.
        String text = "SMM-специалист для международных проектов\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_56", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_titleNamesPlatformNotCompany_fallsBackToChannel() {
        // "Менеджер по продажам в Telegram" — Telegram здесь платформа работы, а не
        // работодатель; без блэклиста это стало бы company="Telegram" для десятков
        // разных, никак не связанных вакансий.
        String text = "Менеджер по продажам в Telegram\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_57", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_wordCompanyWithoutColon_doesNotMisfireAsEmployerLabel() {
        // "Компания развивает собственные бренды..." — слово "компания" в обычном
        // предложении, не лейбл поля. Без требования двоеточия это захватывалось
        // regex'ом как "работодатель: развивает собственные бренды..." — бессмыслица.
        String text = "UGC-креатор в WAPS\n\nКомпания развивает собственные бренды на маркетплейсах.";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_58", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("WAPS", repo.saved.get(0).getCompany(), "должен взять компанию из заголовка, не смысловое совпадение по слову «компания»");
    }

    @Test
    void discoverFromTelegram_sameTitleNoEmployer_sharesDedupKeyAcrossReposts() {
        // Живой пример: канал-агрегатор репостит одну и ту же вакансию несколько раз,
        // каждый раз с чуть другой формулировкой (разные markdown-заголовки, вводные
        // фразы) — line-similarity между копиями измерена ~0.53, ниже порога 0.85, так
        // что description-hash дедуп их не ловит. Без извлечённого работодателя ключ
        // должен строиться по title+channel, чтобы такие повторы схлопывались обычным
        // дедупом (dedupeByKey/findFirstScrapedByDedupKey), как раньше.
        String text1 = "Асессор\n\nКаждый день миллионы людей смотрят контент...";
        String text2 = "Асессор\n\nОбязанности:\n- Разметка контента\n- Фильтрация...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_61", text1), tgMsg("tg_testchan_62", text2)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(2, repo.saved.size());
        assertEquals(repo.saved.get(0).getDedupKey(), repo.saved.get(1).getDedupKey(),
            "разный текст, но тот же заголовок и тот же канал без явного работодателя — один dedup_key");
    }

    @Test
    void discoverFromTelegram_organizationAsDutiesSubheading_doesNotMisfireAsEmployer() {
        // Живой пример: "Организация:" использовано как подзаголовок раздела обязанностей
        // ("организовывать процесс"), а не как метка "название организации-работодателя".
        // "организация" убрана из ключевых слов, и на всякий случай ловим ещё и общий
        // признак — захваченное значение начинается с маркера списка ("—").
        String text = "Ищу ассистента с навыками создания контента\n\n"
            + "Обязанности:\n— вести соцсети;\n\nОрганизация:\n— ставить задачи и контролировать выполнение;\n— следить за дедлайнами;";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_63", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany(),
            "не должен принять фрагмент списка обязанностей за название работодателя");
    }

    @Test
    void discoverFromTelegram_labeledSalary_extractsFromAndCurrency() {
        String text = "SMM-специалист\n\nЗаработная плата от 40000 рублей\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_70", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertEquals(40000, v.getSalaryFrom());
        assertNull(v.getSalaryTo());
        assertEquals("RUR", v.getCurrency());
    }

    @Test
    void discoverFromTelegram_bareSalaryRangeNearTitle_extractsFromAndTo() {
        String text = "Асессор\n60 000 – 250 000 ₽\n\nОбязанности: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_71", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertEquals(60000, v.getSalaryFrom());
        assertEquals(250000, v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_bareNumberDeepInText_notMistakenForSalary() {
        // "350₽" встречается в конце поста как цена продвижения самого поста в канале —
        // это НЕ зарплата вакансии; вне первых SALARY_SCAN_LINES строк и без метки не
        // должно приниматься, лучше "не указана", чем неверная цифра.
        String text = "Оператор чата\n\nОбязанности: отвечать на сообщения\n\nТребования: без опыта\n\n"
            + "Реклама: 2 часа в топ - 350₽";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_72", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertNull(v.getSalaryFrom());
        assertNull(v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_noSalaryMentioned_leavesSalaryUnset() {
        String text = "Копирайтер\n\nОбязанности: пишет тексты\n\nОплата по договорённости";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_73", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertNull(v.getSalaryFrom());
        assertNull(v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_pathA_urlIsRealHhVacancyLinkNotTelegramPost() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Менеджер по продажам\nПодробности: https://ufa.hh.ru/vacancy/123456789")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://ufa.hh.ru/vacancy/123456789", repo.saved.get(0).getUrl());
    }

    @Test
    void discoverFromTelegram_pathB_externalLinkPreferredOverTelegramPostLink() {
        // Живой пример: канал-агрегатор (kadrout) постит только тизер со ссылкой на
        // полное описание на своём сайте — не job-борд, который мы умеем скрейпить
        // (остаётся Path B), но ссылка полезнее для читателя, чем усечённый tg-пост.
        String text = "SEO-копирайтер\n\nОбязанности: ...\n\nПосмотреть вакансию полностью: https://kadrout.ru/vacancies/38184/seo?utm_source=tg.";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_80", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://kadrout.ru/vacancies/38184/seo?utm_source=tg", repo.saved.get(0).getUrl(),
            "внешняя ссылка на первоисточник предпочтительнее ссылки на сам telegram-пост, конечная точка обрезана как пунктуация");
    }

    @Test
    void discoverFromTelegram_pathB_noExternalLink_fallsBackToTelegramPostLink() {
        String text = "Копирайтер\n\nОбязанности: пишет тексты\n\nПишите в лс";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_81", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://t.me/testchan/tg_testchan_81", repo.saved.get(0).getUrl());
    }

    @Test
    void discoverFromTelegram_excludeWordMatch_dropsCandidateBeforeSaving() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Риэлтор без опыта, удалённо, доход от 100000")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);
        SearchJob job = tgJob();
        job.excludeWords = List.of("риэлтор");

        int saved = svc.discoverFromTelegram(job, List.of("testchan"));

        assertEquals(0, saved, "риэлторские посты должны отсеиваться так же, как для hh.ru-источника");
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void discoverFromTelegram_alreadyKnownMessageId_skipsSavingAgain() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_7", "Продавец-консультант удалённо")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of("tg_testchan_7"));
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, tg, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);

        int saved = svc.discoverFromTelegram(tgJob(), List.of("testchan"));

        assertEquals(0, saved, "уже сохранённое на прошлом прогоне сообщение не должно сохраняться повторно");
    }

    // ── публикация батчами + динамический темп + окно 07:00–23:00 ──

    private static long dynamicPaceMinutes(Integer basePaceMinutes, int queuedBatches) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("dynamicPaceMinutes", Integer.class, int.class);
        m.setAccessible(true);
        return (long) m.invoke(null, basePaceMinutes, queuedBatches);
    }

    private static java.time.Instant pushPastNightWindow(java.time.Instant candidate) throws Exception {
        Method m = VacancyPipelineService.class.getDeclaredMethod("pushPastNightWindow", java.time.Instant.class);
        m.setAccessible(true);
        return (java.time.Instant) m.invoke(null, candidate);
    }

    private static java.time.Instant localInstant(int hour, int minute) {
        return java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant();
    }

    @Test
    void dynamicPaceMinutes_emptyQueue_usesBasePace() throws Exception {
        assertEquals(5, dynamicPaceMinutes(5, 0));
    }

    @Test
    void dynamicPaceMinutes_deepQueue_shortensInterval() throws Exception {
        // base=5, REFERENCE_QUEUE_BATCHES=5, 6 батчей в очереди — чуть глубже эталона,
        // ещё выше MIN_PACE_MINUTES (=3), так что виден именно эффект укорачивания,
        // а не отдельно проверяемый clamp.
        assertEquals(4, dynamicPaceMinutes(5, 6), "5*5/6 = 4.16 -> округление вниз до 4, короче базовых 5 мин");
    }

    @Test
    void dynamicPaceMinutes_shallowQueue_lengthensInterval() throws Exception {
        // base=5, REFERENCE_QUEUE_BATCHES=5, всего 1 батч в очереди — впятеро реже эталона.
        assertEquals(25, dynamicPaceMinutes(5, 1));
    }

    @Test
    void dynamicPaceMinutes_extremelyDeepQueue_neverBelowMinPace() throws Exception {
        assertEquals(3, dynamicPaceMinutes(5, 10_000), "не должен уходить ниже MIN_PACE_MINUTES независимо от размера очереди");
    }

    @Test
    void dynamicPaceMinutes_extremelyShallowQueue_neverAboveMaxPace() throws Exception {
        assertEquals(60, dynamicPaceMinutes(100, 1), "не должен уходить выше MAX_PACE_MINUTES независимо от базового темпа");
    }

    @Test
    void pushPastNightWindow_daytimeInstant_unchanged() throws Exception {
        java.time.Instant daytime = localInstant(14, 30);
        assertEquals(daytime, pushPastNightWindow(daytime));
    }

    @Test
    void pushPastNightWindow_earlyMorningInstant_pushedToWindowStartSameDay() throws Exception {
        java.time.Instant earlyMorning = localInstant(2, 0);
        java.time.Instant result = pushPastNightWindow(earlyMorning);
        java.time.ZonedDateTime zdt = result.atZone(java.time.ZoneId.systemDefault());
        assertEquals(7, zdt.getHour());
        assertEquals(localInstant(2, 0).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), zdt.toLocalDate(),
            "02:00 должно сдвинуться на 07:00 ТОГО ЖЕ дня, не следующего");
    }

    @Test
    void pushPastNightWindow_lateEveningInstant_pushedToWindowStartNextDay() throws Exception {
        java.time.Instant lateEvening = localInstant(23, 30);
        java.time.Instant result = pushPastNightWindow(lateEvening);
        java.time.ZonedDateTime zdt = result.atZone(java.time.ZoneId.systemDefault());
        java.time.ZonedDateTime originalZdt = lateEvening.atZone(java.time.ZoneId.systemDefault());
        assertEquals(7, zdt.getHour());
        assertEquals(originalZdt.toLocalDate().plusDays(1), zdt.toLocalDate(),
            "23:30 должно сдвинуться на 07:00 СЛЕДУЮЩЕГО дня");
    }

    /** Captures enqueuePublish calls; findQueueTailTime/countQueued report an empty queue. */
    private static class FakeQueueRepo extends VacancyRepository {
        final List<Long> enqueuedIds = new ArrayList<>();
        final List<String> enqueuedPublishAts = new ArrayList<>();
        FakeQueueRepo() { super(null); }
        @Override
        public java.util.Optional<String> findQueueTailTime(Long searchId) { return java.util.Optional.empty(); }
        @Override
        public int countQueued(Long searchId) { return 0; }
        @Override
        public void enqueuePublish(List<Long> ids, List<String> publishAts) {
            enqueuedIds.addAll(ids);
            enqueuedPublishAts.addAll(publishAts);
        }
    }

    @Test
    void enqueuePublicPosts_batchesFiveVacanciesPerDueTime() throws Exception {
        // 12 одобренных вакансий должны лечь в очередь по 5 — три группы с тремя разными
        // (не более) queued_publish_at, а не 12 разных моментов времени.
        FakeQueueRepo repo = new FakeQueueRepo();
        VacancyPipelineService svc = new VacancyPipelineService(
            null, null, null, null, repo, null, new RuntimeConfig(), null, new FeatureFlags(), null, null);
        SearchJob job = tgJob();
        job.chatId = "-100123";
        job.publishPaceMinutes = 5;

        List<Vacancy> approved = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Vacancy v = new Vacancy();
            v.setId((long) (i + 1));
            v.setAiScore(80);
            approved.add(v);
        }

        Method m = VacancyPipelineService.class.getDeclaredMethod("sendPublicPosts", List.class, SearchJob.class);
        m.setAccessible(true);
        m.invoke(svc, approved, job);

        assertEquals(12, repo.enqueuedIds.size());
        java.util.Set<String> distinctTimes = new java.util.HashSet<>(repo.enqueuedPublishAts);
        assertEquals(3, distinctTimes.size(), "12 вакансий по 5 в батче -> 3 разных момента публикации (5+5+2)");
    }
}
