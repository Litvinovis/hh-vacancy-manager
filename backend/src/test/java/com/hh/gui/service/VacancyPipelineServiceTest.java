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
        service = service().build();
    }

    /**
     * Builds the service with only the collaborators a given test actually cares about;
     * everything else stays null, exactly as each call site spelled out by hand before.
     *
     * The point is the constructor's 12 arguments were repeated across 45 call sites, so
     * every new dependency meant 45 mechanical edits — paid three times in one session
     * (TelegramClient, TelegramMetrics, the AiResult signature), and one of those bulk
     * edits missed a site and broke the build. Now a 13th dependency changes one line:
     * {@link Builder#build()}.
     */
    private static Builder service() { return new Builder(); }

    private static final class Builder {
        private ScraperClient scraper;
        private TelegramClient telegram;
        private VacancyAiAnalyzer analyzer;
        private VacancyRepository repo;
        private TelegramNotifier notifier;
        private RuntimeConfig config = new RuntimeConfig();
        private FeatureFlags featureFlags = new FeatureFlags();
        private com.hh.gui.ai.AiMetrics aiMetrics;
        private com.hh.gui.repository.SearchRepository searchRepo;
        private io.micrometer.core.instrument.MeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        private ScrapeCooldown cooldown = new ScrapeCooldown();
        private SearchProfileFactory profileFactory;

        Builder scraper(ScraperClient v) { this.scraper = v; return this; }
        Builder telegram(TelegramClient v) { this.telegram = v; return this; }
        Builder analyzer(VacancyAiAnalyzer v) { this.analyzer = v; return this; }
        Builder repo(VacancyRepository v) { this.repo = v; return this; }
        Builder notifier(TelegramNotifier v) { this.notifier = v; return this; }
        Builder config(RuntimeConfig v) { this.config = v; return this; }
        Builder featureFlags(FeatureFlags v) { this.featureFlags = v; return this; }
        Builder aiMetrics(com.hh.gui.ai.AiMetrics v) { this.aiMetrics = v; return this; }
        Builder searchRepo(com.hh.gui.repository.SearchRepository v) { this.searchRepo = v; return this; }
        /** Only when a test asserts on the recorded metrics and needs the registry back. */
        Builder metricsRegistry(io.micrometer.core.instrument.MeterRegistry v) { this.registry = v; return this; }
        /** Shared with the caller when a test asserts on the scrape freeze. */
        Builder cooldown(ScrapeCooldown v) { this.cooldown = v; return this; }
        /** Only when a test actually reaches the moderation-enabled path. */
        Builder profileFactory(SearchProfileFactory v) { this.profileFactory = v; return this; }

        VacancyPipelineService build() {
            TelegramMetrics tgMetrics = new TelegramMetrics(registry);
            // Most tests never set .aiMetrics(...) explicitly — falling back to a real
            // (if unasserted-on) instance instead of null, now that VacancyDiscovery
            // unconditionally records collected-vacancy counts through it on every
            // fromRss/fromUrl call.
            com.hh.gui.ai.AiMetrics resolvedAiMetrics = aiMetrics != null
                ? aiMetrics : new com.hh.gui.ai.AiMetrics(registry, new RuntimeConfig());
            // Wired the same way production is, so a test that does reach the public-format
            // path gets real publishing behaviour rather than a null collaborator.
            ChannelPublisher publisher = new ChannelPublisher(repo, searchRepo, notifier, tgMetrics, config);
            ChannelEngagementTracker engagement =
                new ChannelEngagementTracker(searchRepo, telegram, notifier, tgMetrics);
            VacancyDiscovery discovery = new VacancyDiscovery(null, scraper, telegram, analyzer, repo,
                tgMetrics, engagement, cooldown, resolvedAiMetrics);
            ModerationService moderationService = new ModerationService(repo, profileFactory, publisher, notifier);
            return new VacancyPipelineService(scraper, analyzer, repo, notifier,
                config, resolvedAiMetrics, featureFlags, null, tgMetrics, publisher, discovery, cooldown,
                moderationService);
        }
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

    // ── formatPublicPost's "apply here" line: falls back to a contact when the stored
    // url is just the Telegram post's own self-link (live bug: freelancce post "Отклик:
    // sasha@fond-igra.ru" rendered "👉 https://t.me/freelancce/15611" — a dead link back
    // to the post itself, not anywhere the reader could actually apply) ──

    private Vacancy telegramSelfLinkVacancy(String description) {
        Vacancy v = new Vacancy();
        v.setHhId("tg_freelancce_15611");
        v.setTitle("Креатор в благотворительный фонд «Игра»");
        v.setCompany("@freelancce");
        v.setAiScore(80);
        v.setAiVerdict("yes");
        v.setUrl("https://t.me/freelancce/15611");
        v.setDescription(description);
        return v;
    }

    @Test
    void formatPublicPost_selfLinkWithEmailInDescription_showsEmailInstead() {
        String post = service.formatPublicPost(telegramSelfLinkVacancy(
            "Фонд ищет специалиста.\n\nОтклик:\n sasha@fond-igra.ru"));
        assertTrue(post.contains("📧 sasha@fond-igra.ru"), post);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
    }

    @Test
    void formatPublicPost_selfLinkWithPhoneInDescription_showsPhoneInstead() {
        String post = service.formatPublicPost(telegramSelfLinkVacancy(
            "Звоните: +7 495 123 45 67, обсудим детали."));
        assertTrue(post.contains("📞") && post.contains("495") && post.contains("67"), post);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
    }

    @Test
    void formatPublicPost_selfLinkWithPersonalContactNextToKeyword_showsTelegramLink() {
        String post = service.formatPublicPost(telegramSelfLinkVacancy(
            "Для отклика напиши Ассистент в лс @azatka_kzn"));
        assertTrue(post.contains("💬 <a href=\"https://t.me/azatka_kzn\">Откликнуться</a>"), post);
    }

    @Test
    void formatPublicPost_atMentionWithoutContactKeyword_notTreatedAsContact() {
        // A bare @channel mention unrelated to "how to apply" (e.g. crediting where the
        // post came from) must not be misread as the reader's contact. No usable contact
        // means no apply line at all (see selfLinkNoContactFound below) — the self-link
        // itself is never shown either way.
        String post = service.formatPublicPost(telegramSelfLinkVacancy(
            "Репост из @somechannel, вакансия интересная."));
        assertFalse(post.contains("t.me/freelancce/15611"), post);
        assertFalse(post.contains("t.me/somechannel"), post);
    }

    @Test
    void formatPublicPost_selfLinkNoContactFound_omitsApplyLineRatherThanShowingDeadLink() {
        // Found live: 65 of 359 sent Telegram-sourced posts had this exact shape and
        // fell back to printing the self-link as "Откликнуться" — a link to another
        // channel's post, exactly what the self-link check exists to prevent.
        String post = service.formatPublicPost(telegramSelfLinkVacancy(
            "Просто описание без каких-либо контактов."));
        assertFalse(post.contains("t.me/freelancce/15611"), post);
        assertFalse(post.contains("👉"), post);
    }

    @Test
    void formatPublicPost_realExternalUrl_unaffectedByContactExtraction() {
        // Path A / external-job-board posts never hit the self-link fallback at all.
        Vacancy v = vacancy("Продавец", "Подходит", 80);
        v.setDescription("Отклик: someone@example.com");
        String post = service.formatPublicPost(v);
        assertTrue(post.contains("👉 <a href=\"https://hh.ru/vacancy/1\">Откликнуться</a>"), post);
        assertFalse(post.contains("someone@example.com"), post);
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
            super(config, new com.hh.gui.client.ScraperMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
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
            super(config, null, null, null);
        }
        @Override
        public List<AiResult> prescreenHits(List<ScraperClient.SearchHit> hits, SearchJob job) {
            return List.of();
        }
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
                                    Integer aiSalaryFrom, Integer aiSalaryTo, String aiCurrency, String aiCompany, String aiTitle) {
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
        FakeBatchAnalyzer(RuntimeConfig config) { super(config, null, null, null); }
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
        VacancyPipelineService svc = service().analyzer(analyzer).repo(repo).config(config).aiMetrics(new com.hh.gui.ai.AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), config)).build();

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
        FreshnessScraper(RuntimeConfig config) {
            super(config, new com.hh.gui.client.ScraperMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        }
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
        ScrapeCooldown cooldown = new ScrapeCooldown();
        VacancyPipelineService svc = service().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).config(config).cooldown(cooldown).build();

        int count = scrapePending(svc, urlJob());

        assertEquals(10, count, "все 10 legacy-403 должны быть обработаны без остановки по бёрсту");
        assertFalse(cooldown.isCoolingDown(), "legacy-403 не должны замораживать скрейпинг");
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
        ScrapeCooldown cooldown = new ScrapeCooldown();
        VacancyPipelineService svc = service().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).config(config).cooldown(cooldown).build();

        scrapePending(svc, urlJob());

        assertTrue(cooldown.isCoolingDown(), "бёрст свежих 403 должен по-прежнему замораживать скрейпинг");
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
        VacancyPipelineService svc = service().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).config(config).build();

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
        VacancyPipelineService svc = service().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).config(config).build();

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
        VacancyPipelineService svc = service().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).config(config).build();

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
        VacancyPipelineService svc = service().config(config).build();

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
        VacancyPipelineService svc = service().config(config).build();

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
        final List<Long> markedModerationQueued = new ArrayList<>();
        FakeSimilarityRepo() { super(null); }
        @Override
        public List<Vacancy> findNotifiedByEmployer(String person, String searchName, String chatId, String employerName) {
            return alreadyNotified;
        }
        @Override
        public List<Vacancy> findWithUnresolvedEmployer(String person, String searchName, String chatId) {
            return unresolvedEmployerPool;
        }
        @Override
        public void markNotified(List<Long> ids) {
            markedNotified.addAll(ids);
        }
        @Override
        public void markModerationQueued(List<Long> ids) {
            markedModerationQueued.addAll(ids);
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

    /** Public-format sends go through sendViaChannelBot, not send() — see TelegramNotifier. */
    private static class RecordingChannelNotifier extends TelegramNotifier {
        final List<String> sent = new ArrayList<>();
        @Override
        public boolean sendViaChannelBot(String message, String targetChatId) {
            sent.add(message);
            return true;
        }
    }

    private static class TogglableFlags extends FeatureFlags {
        @Override
        public boolean isPublicFormatEnabled() { return true; }
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
        VacancyPipelineService svc = service().repo(repo).notifier(new RecordingNotifier()).config(config).build();

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
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config).build();

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
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config).build();

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

    // ── Качественный фильтр публичных постов: без компании И без зарплаты не публикуем ──

    private SearchJob publicJob() {
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Без техстека";
        job.chatId = "-1004333110303";
        job.publicFormat = true;
        return job;
    }

    @Test
    void sendReport_publicFormat_noCompanyAndNoSalary_isDroppedNotSent() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new TogglableFlags()).build();

        Vacancy candidate = vacancy("Оператор call-центра", "Подходит", 70);
        candidate.setId(70L);
        candidate.setCompany(""); // нет ни реального работодателя, ни @-заглушки
        candidate.setDescription("Обязанности: приём звонков\nТребования: без опыта");
        // salaryFrom/salaryTo остаются дефолтными (0) — как у vacancy() без явного вызова setSalaryFrom/To

        sendReport(svc, List.of(candidate), publicJob());

        assertTrue(notifier.sent.isEmpty(), "вакансия без компании и без зарплаты не должна публиковаться в канал");
        assertEquals(List.of(70L), repo.markedNotified,
            "отброшенная вакансия должна быть помечена notified, иначе findUnnotifiedApproved вернёт её снова");
    }

    @Test
    void sendReport_publicFormat_hasSalaryButNoCompany_isKept() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new TogglableFlags()).build();

        Vacancy candidate = vacancy("Оператор call-центра", "Подходит", 70);
        candidate.setId(71L);
        candidate.setCompany("@rabota_onlaynr"); // заглушка — трактуется как "нет компании"
        candidate.setSalaryFrom(40000);
        candidate.setDescription("Обязанности: приём звонков\nТребования: без опыта");

        sendReport(svc, List.of(candidate), publicJob());

        assertEquals(1, notifier.sent.size(), "с указанной зарплатой отсутствие компании не должно блокировать публикацию");
    }

    @Test
    void sendReport_personalFormat_noCompanyAndNoSalary_stillSent() throws Exception {
        // Фильтр качества применяется только к публичному формату — личный поиск
        // пользователя не должен терять вакансии просто из-за отсутствия этих полей.
        RuntimeConfig config = new RuntimeConfig();
        config.setNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingNotifier notifier = new RecordingNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config).build();

        Vacancy candidate = vacancy("Продавец", "Подходит", 75);
        candidate.setId(72L);
        candidate.setCompany("");
        candidate.setDescription("Обязанности: продавать\nТребования: опыт");

        SearchJob job = new SearchJob();
        job.personName = "Мама";
        job.searchName = "Рядом с домом";

        sendReport(svc, List.of(candidate), job);

        assertEquals(1, notifier.sent.size(), "личный отчёт не фильтруется по компании/зарплате");
    }

    // ── Ручная модерация публичных постов ──

    private static class ModerationEnabledFlags extends FeatureFlags {
        @Override public boolean isPublicFormatEnabled() { return true; }
        @Override public boolean isModerationEnabled() { return true; }
    }

    private SearchJob editorialJob() {
        SearchJob job = publicJob();
        job.kind = com.hh.gui.model.SearchKind.EDITORIAL;
        return job;
    }

    @Test
    void sendReport_editorialWithModerationEnabled_queuesInsteadOfPublishing() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy candidate = vacancy("Оператор поддержки", "Подходит", 75);
        candidate.setId(80L);
        candidate.setSalaryFrom(50000);
        candidate.setDescription("Обязанности: отвечать клиентам\nТребования: без опыта");

        sendReport(svc, List.of(candidate), editorialJob());

        assertTrue(notifier.sent.isEmpty(), "модерация должна перехватить публикацию — прямой отправки быть не должно");
        assertEquals(List.of(80L), repo.markedModerationQueued);
    }

    @Test
    void sendReport_editorialWithModerationDisabled_publishesDirectly() throws Exception {
        // Контрольный случай: тот же EDITORIAL-джоб, но флаг модерации выключен —
        // должен работать ровно как раньше, без задержки на человека.
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new TogglableFlags()).build();

        Vacancy candidate = vacancy("Оператор поддержки", "Подходит", 75);
        candidate.setId(81L);
        candidate.setSalaryFrom(50000);
        candidate.setDescription("Обязанности: отвечать клиентам\nТребования: без опыта");

        sendReport(svc, List.of(candidate), editorialJob());

        assertEquals(1, notifier.sent.size(), "без включённой модерации публикация должна идти как раньше");
        assertTrue(repo.markedModerationQueued.isEmpty());
    }

    // ── Автоапрув по score (выключен по умолчанию) ──

    @Test
    void sendReport_autoApproveThreshold_highScorePublishesDirectly() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        config.setAutoApproveScoreThreshold(90);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy highScore = vacancy("Оператор поддержки", "Идеально подходит", 95);
        highScore.setId(90L);
        highScore.setSalaryFrom(50000);
        highScore.setDescription("Обязанности: отвечать клиентам\nТребования: без опыта");

        sendReport(svc, List.of(highScore), editorialJob());

        assertEquals(1, notifier.sent.size(), "score выше порога — публикуется сразу, минуя модерацию");
        assertTrue(repo.markedModerationQueued.isEmpty());
    }

    @Test
    void sendReport_autoApproveThreshold_lowScoreStillQueuedForModeration() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        config.setAutoApproveScoreThreshold(90);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy lowScore = vacancy("Оператор поддержки", "Подходит", 70);
        lowScore.setId(91L);
        lowScore.setSalaryFrom(50000);
        lowScore.setDescription("Обязанности: отвечать клиентам\nТребования: без опыта");

        sendReport(svc, List.of(lowScore), editorialJob());

        assertTrue(notifier.sent.isEmpty(), "score ниже порога — как раньше, ждёт модерации");
        assertEquals(List.of(91L), repo.markedModerationQueued);
    }

    @Test
    void sendReport_autoApproveThreshold_mixedBatchSplitsCorrectly() throws Exception {
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        config.setAutoApproveScoreThreshold(90);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy highScore = vacancy("Оператор поддержки", "Идеально", 95);
        highScore.setId(92L);
        highScore.setSalaryFrom(50000);
        highScore.setDescription("A");
        Vacancy lowScore = vacancy("Курьер", "Подходит", 60);
        lowScore.setId(93L);
        lowScore.setSalaryFrom(40000);
        lowScore.setDescription("B");

        sendReport(svc, List.of(highScore, lowScore), editorialJob());

        assertEquals(1, notifier.sent.size(), "только высокий score публикуется напрямую");
        assertEquals(List.of(93L), repo.markedModerationQueued, "только низкий score идёт на модерацию");
    }

    @Test
    void sendReport_autoApproveThresholdZero_disabledByDefault_allGoToModeration() throws Exception {
        // Порог не задан (значение по умолчанию RuntimeConfig) — поведение не отличается
        // от sendReport_editorialWithModerationEnabled_queuesInsteadOfPublishing.
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        assertEquals(0, config.getAutoApproveScoreThreshold(), "автоапрув должен быть выключен по умолчанию");
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy veryHighScore = vacancy("Оператор поддержки", "Идеально", 100);
        veryHighScore.setId(94L);
        veryHighScore.setSalaryFrom(50000);
        veryHighScore.setDescription("A");

        sendReport(svc, List.of(veryHighScore), editorialJob());

        assertTrue(notifier.sent.isEmpty(), "даже score=100 не должен обходить модерацию, если порог выключен");
        assertEquals(List.of(94L), repo.markedModerationQueued);
    }

    @Test
    void sendReport_personalKindWithModerationEnabled_stillPublishesDirectly() throws Exception {
        // Модерация специально ограничена EDITORIAL — публичный формат на PERSONAL-джобе
        // (если такая комбинация вообще встретится) не должен неожиданно зависать на человеке.
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);
        FakeSimilarityRepo repo = new FakeSimilarityRepo();
        RecordingChannelNotifier notifier = new RecordingChannelNotifier();
        VacancyPipelineService svc = service().repo(repo).notifier(notifier).config(config)
            .featureFlags(new ModerationEnabledFlags()).build();

        Vacancy candidate = vacancy("Оператор поддержки", "Подходит", 75);
        candidate.setId(82L);
        candidate.setSalaryFrom(50000);
        candidate.setDescription("Обязанности: отвечать клиентам\nТребования: без опыта");

        sendReport(svc, List.of(candidate), publicJob()); // kind остаётся PERSONAL по умолчанию

        assertEquals(1, notifier.sent.size());
        assertTrue(repo.markedModerationQueued.isEmpty());
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
            super(null, null, null, null, config, null, new FeatureFlags(), null,
                new TelegramMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), null, null, null, null);
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
        assertTrue(skipped.skipped, "пропущенный запуск должен быть помечен как skipped — иначе вызывающий планировщик решит, что поиск реально выполнился");
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
            "https://t.me/testchan/" + id, "testchan", "telegram", null, java.util.Map.of());
    }

    private static TelegramClient.TelegramMessage tgMsg(String id, String text, Integer views, java.util.Map<String, Integer> reactions) {
        return new TelegramClient.TelegramMessage(id, text, "2026-08-15T09:00:00.000Z",
            "https://t.me/testchan/" + id, "testchan", "telegram", views, reactions);
    }

    private static class FakeTelegramClient extends TelegramClient {
        final java.util.Map<String, ChannelResult> byChannel;
        FakeTelegramClient(java.util.Map<String, ChannelResult> byChannel) { this.byChannel = byChannel; }
        @Override
        public ChannelResult fetchChannel(String username, int limit) {
            return byChannel.getOrDefault(username, new ChannelResult(true, null, List.of()));
        }
    }


    // ── публикация батчами + динамический темп + окно 07:00–23:00 ──

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
}
