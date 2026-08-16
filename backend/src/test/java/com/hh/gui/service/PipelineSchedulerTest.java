package com.hh.gui.service;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.ai.FreeModelUpdater;
import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.config.FeatureFlags;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.config.SchemaMigrator;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduler is what actually drives production — eleven triggers, and until now
 * zero tests. A break here is silent in the worst way: nothing runs, or everything
 * runs far too often, and no request fails to tell you.
 *
 * Tests drive the real {@link ScheduledTaskRegistrar}, so registration itself is
 * covered, and then run every registered task and assert on what the collaborators
 * saw. Running all of them each time (rather than picking one by index) keeps the
 * tests independent of the order tasks happen to be registered in.
 */
class PipelineSchedulerTest {

    // ── fakes ──

    static class RecordingPipeline extends VacancyPipelineService {
        final List<String> fullRuns = new ArrayList<>();
        final List<String> urlRuns = new ArrayList<>();
        final List<String> telegramRuns = new ArrayList<>();
        final List<String> analyzedAll = new ArrayList<>();
        int freshnessChecks = 0;
        /** Search names that should blow up when run, to test error isolation. */
        final List<String> failOn = new ArrayList<>();
        /** Search names that should report "skipped by lock contention" — see skippedByLock handling. */
        final List<String> skipOn = new ArrayList<>();

        RecordingPipeline(RuntimeConfig config) {
            super(null, null, null, null, config, null, new FeatureFlags(), null,
                new TelegramMetrics(new SimpleMeterRegistry()), null, null, null);
        }
        private void maybeFail(SearchJob job) {
            if (failOn.contains(job.searchName)) throw new IllegalStateException("сломался поиск " + job.searchName);
        }
        private PipelineResult result(SearchJob job) {
            PipelineResult result = new PipelineResult();
            result.skipped = skipOn.contains(job.searchName);
            return result;
        }
        @Override
        public PipelineResult runFullPipeline(SearchJob job, boolean deferSmallAiBatches) {
            fullRuns.add(job.searchName);
            maybeFail(job);
            return result(job);
        }
        @Override
        public PipelineResult runFullPipelineFromUrl(SearchJob job, String url, int maxPages) {
            urlRuns.add(job.searchName);
            maybeFail(job);
            return result(job);
        }
        @Override
        public PipelineResult runFullPipelineFromTelegram(SearchJob job, List<String> channels) {
            telegramRuns.add(job.searchName);
            maybeFail(job);
            return result(job);
        }
        @Override
        public int analyzeAllPending(SearchJob job) { analyzedAll.add(job.searchName); return 0; }
        @Override
        public FreshnessResult checkVacancyFreshness(int limit) { freshnessChecks++; return new FreshnessResult(); }
    }

    static class FakeProfileFactory extends SearchProfileFactory {
        List<SearchJob> jobs = new ArrayList<>();
        FakeProfileFactory() { super(null, null); }
        @Override
        public List<SearchJob> build() { return jobs; }
        @Override
        public Optional<SearchJob> buildForSearchId(Long searchId) {
            return jobs.stream().filter(j -> searchId.equals(j.searchId)).findFirst();
        }
    }

    static class FakeSearchRepo extends SearchRepository {
        List<SearchConfig> urlSearches = new ArrayList<>();
        List<SearchConfig> telegramSearches = new ArrayList<>();
        final List<Long> stamped = new ArrayList<>();
        FakeSearchRepo() { super(null); }
        @Override
        public List<SearchConfig> findScheduledUrlSearches() { return urlSearches; }
        @Override
        public List<SearchConfig> findScheduledTelegramSearches() { return telegramSearches; }
        @Override
        public void updateLastRunAt(Long id, String at) { stamped.add(id); }
    }

    static class FakeAnalyzer extends VacancyAiAnalyzer {
        boolean rateLimited = false;
        FakeAnalyzer(RuntimeConfig config) { super(config, null, new AiMetrics(new SimpleMeterRegistry(), config)); }
        @Override
        public boolean isRateLimited() { return rateLimited; }
    }

    static class FakeFreeModelUpdater extends FreeModelUpdater {
        int refreshes = 0;
        FakeFreeModelUpdater(RuntimeConfig config) { super(config, null); }
        @Override
        public java.util.Map<String, Object> refresh() { refreshes++; return java.util.Map.of(); }
    }

    static class FakeSchemaMigrator extends SchemaMigrator {
        boolean ready = true;
        FakeSchemaMigrator() { super(null); }
        @Override
        public boolean isReady() { return ready; }
    }

    static class FakeSubscriptions extends SubscriptionService {
        int expiries = 0;
        int reminders = 0;
        FakeSubscriptions() { super(null, null, null); }
        @Override
        public int expireDue() { expiries++; return 0; }
        @Override
        public int sendDueRenewalReminders() { reminders++; return 0; }
    }

    static class FakePublisher extends ChannelPublisher {
        int queuedTicks = 0;
        int delayedTicks = 0;
        FakePublisher() { super(null, null, null, null, null); }
        @Override
        public void publishDueQueued(int limit) { queuedTicks++; }
        @Override
        public void publishDueDelayed(int limit) { delayedTicks++; }
    }

    static class FakeEngagement extends ChannelEngagementTracker {
        int subscriberChecks = 0;
        int ownChannelChecks = 0;
        FakeEngagement() { super(null, null, null, null); }
        @Override
        public void checkSubscribers() { subscriberChecks++; }
        @Override
        public void checkOwnChannels() { ownChannelChecks++; }
    }

    static class FakeVacancyRepository extends VacancyRepository {
        final List<String> deleteCutoffs = new ArrayList<>();
        int deleteReturns = 0;
        FakeVacancyRepository() { super(null); }
        @Override
        public int deleteOlderThan(String cutoffCreatedAt) {
            deleteCutoffs.add(cutoffCreatedAt);
            return deleteReturns;
        }
    }

    static class TogglableFlags extends FeatureFlags {
        boolean delayedPublish = true;
        boolean subscriptions = true;
        @Override
        public boolean isDelayedPublishEnabled() { return delayedPublish; }
        @Override
        public boolean isSubscriptionsEnabled() { return subscriptions; }
    }

    // ── fixture ──

    private RuntimeConfig config;
    private RecordingPipeline pipeline;
    private FakeProfileFactory profiles;
    private FakeSearchRepo searchRepo;
    private FakeAnalyzer analyzer;
    private FakeFreeModelUpdater freeModels;
    private FakeSchemaMigrator schema;
    private FakeSubscriptions subscriptions;
    private FakePublisher publisher;
    private FakeEngagement engagement;
    private TogglableFlags flags;
    private FakeVacancyRepository vacancyRepo;
    private PipelineScheduler scheduler;

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig();
        config.setPipelineEnabled(true);
        pipeline = new RecordingPipeline(config);
        profiles = new FakeProfileFactory();
        searchRepo = new FakeSearchRepo();
        analyzer = new FakeAnalyzer(config);
        freeModels = new FakeFreeModelUpdater(config);
        schema = new FakeSchemaMigrator();
        subscriptions = new FakeSubscriptions();
        publisher = new FakePublisher();
        engagement = new FakeEngagement();
        flags = new TogglableFlags();
        vacancyRepo = new FakeVacancyRepository();
        scheduler = new PipelineScheduler(pipeline, profiles, config, analyzer, searchRepo,
            freeModels, flags, schema, subscriptions, publisher, engagement, vacancyRepo);
    }

    private List<TriggerTask> tasks() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        return registrar.getTriggerTaskList();
    }

    /** Fires every registered task once — order-independent, so a reordering can't quietly
     *  turn a test into a no-op. */
    private void runAllTasks() {
        for (TriggerTask task : tasks()) {
            task.getRunnable().run();
        }
    }

    private SearchJob job(String name, Long id, String... queries) {
        SearchJob j = new SearchJob();
        j.personName = "Тест";
        j.searchName = name;
        j.searchId = id;
        j.queries = List.of(queries);
        j.sourceUrl = "https://hh.ru/search/vacancy?text=x";
        j.telegramChannels = List.of("somechan");
        return j;
    }

    private SearchConfig scheduled(long id, String lastRunAt, int intervalHours) {
        SearchConfig s = new SearchConfig();
        s.setId(id);
        s.setLastRunAt(lastRunAt);
        s.setRunIntervalHours(intervalHours);
        return s;
    }

    // ── регистрация ──

    @Test
    void configureTasks_registersEveryTrigger() {
        assertEquals(12, tasks().size(),
            "все триггеры должны быть зарегистрированы — молча пропавший = молча не работающая функция");
    }

    // ── защита от преждевременного запуска ──

    @Test
    void schemaNotReady_stopsEverythingThatTouchesTheDatabase() {
        schema.ready = false;
        profiles.jobs = List.of(job("Поиск", 1L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(1L, null, 6));

        runAllTasks();

        assertTrue(pipeline.fullRuns.isEmpty(), "пайплайн не должен стартовать до готовности схемы");
        assertTrue(pipeline.urlRuns.isEmpty());
        assertTrue(pipeline.analyzedAll.isEmpty());
        assertEquals(0, pipeline.freshnessChecks);
        assertEquals(0, publisher.queuedTicks);
        assertEquals(0, engagement.subscriberChecks);
        assertEquals(0, subscriptions.expiries);
        assertTrue(vacancyRepo.deleteCutoffs.isEmpty(), "retention-очистка не должна стартовать до готовности схемы");
        assertEquals(1, freeModels.refreshes,
            "обновление списка free-моделей к БД не обращается — единственная задача без этой защиты");
    }

    // ── общий выключатель пайплайна ──

    @Test
    void pipelineDisabled_stopsCollectionButStillDrainsTheQueue() {
        config.setPipelineEnabled(false);
        profiles.jobs = List.of(job("Поиск", 1L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(1L, null, 6));
        searchRepo.telegramSearches = List.of(scheduled(1L, null, 6));

        runAllTasks();

        assertTrue(pipeline.fullRuns.isEmpty(), "сбор новых вакансий выключен");
        assertTrue(pipeline.urlRuns.isEmpty());
        assertTrue(pipeline.telegramRuns.isEmpty());
        assertTrue(pipeline.analyzedAll.isEmpty());
        assertEquals(0, pipeline.freshnessChecks);
        assertEquals(1, publisher.queuedTicks,
            "уже одобренное и стоящее в очереди должно дойти до канала — это не «сбор», а доставка");
        assertEquals(1, publisher.delayedTicks);
    }

    // ── охлаждение AI ──

    @Test
    void aiRateLimited_skipsEverythingThatWouldCallTheModel() {
        analyzer.rateLimited = true;
        profiles.jobs = List.of(job("Поиск", 1L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(1L, null, 6));
        searchRepo.telegramSearches = List.of(scheduled(1L, null, 6));

        runAllTasks();

        assertTrue(pipeline.fullRuns.isEmpty(), "во время охлаждения нельзя жечь квоту модели");
        assertTrue(pipeline.urlRuns.isEmpty());
        assertTrue(pipeline.telegramRuns.isEmpty());
        assertTrue(pipeline.analyzedAll.isEmpty());
        assertEquals(1, publisher.queuedTicks, "публикация модель не зовёт и продолжается");
    }

    // ── retention ──

    @Test
    void retentionCleanup_deletesWithA30DayCutoff() {
        Duration maxAge = Duration.ofDays(30);
        Instant before = Instant.now().minus(maxAge);

        runAllTasks();

        assertEquals(1, vacancyRepo.deleteCutoffs.size());
        Instant cutoff = Instant.parse(vacancyRepo.deleteCutoffs.get(0));
        Instant after = Instant.now().minus(maxAge);
        assertFalse(cutoff.isBefore(before), "срез не должен быть раньше 30 дней назад на момент старта теста");
        assertFalse(cutoff.isAfter(after.plusSeconds(1)), "срез должен быть примерно 'сейчас минус 30 дней'");
    }

    @Test
    void retentionCleanup_runsRegardlessOfPipelineToggleOrRateLimit() {
        // В отличие от сбора/AI-анализа, чистка старых данных не завязана ни на
        // общий выключатель пайплайна, ни на охлаждение модели — это обслуживание БД,
        // а не сбор/анализ вакансий.
        config.setPipelineEnabled(false);
        analyzer.rateLimited = true;

        runAllTasks();

        assertEquals(1, vacancyRepo.deleteCutoffs.size());
    }

    // ── флаги функций ──

    @Test
    void featureFlagsOff_disableTheirOwnTasksOnly() {
        flags.delayedPublish = false;
        flags.subscriptions = false;

        runAllTasks();

        assertEquals(0, publisher.delayedTicks, "отложенная публикация выключена флагом");
        assertEquals(0, subscriptions.expiries, "подписки выключены флагом");
        assertEquals(0, subscriptions.reminders);
        assertEquals(1, publisher.queuedTicks, "обычная публикация в канал от этих флагов не зависит");
    }

    // ── какие поиски попадают в основной прогон ──

    @Test
    void mainPipeline_skipsUrlOnlySearches_theyRunOnTheirOwnInterval() {
        SearchJob rss = job("С запросами", 1L, "оператор");
        SearchJob urlOnly = job("Только ссылка", 2L);
        profiles.jobs = List.of(rss, urlOnly);

        runAllTasks();

        assertEquals(List.of("С запросами"), pipeline.fullRuns,
            "поиск без RSS-запросов в основном цикле — это гарантированный no-op, он идёт своим расписанием");
    }

    // ── isDue ──

    @Test
    void neverRunSearch_isDueImmediately() {
        profiles.jobs = List.of(job("Поиск", 7L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(7L, null, 6));

        runAllTasks();

        assertEquals(List.of("Поиск"), pipeline.urlRuns);
    }

    @Test
    void searchRunRecently_isSkippedUntilItsIntervalElapses() {
        profiles.jobs = List.of(job("Поиск", 7L, "оператор"));
        searchRepo.urlSearches = List.of(
            scheduled(7L, Instant.now().minus(Duration.ofHours(1)).toString(), 6));

        runAllTasks();

        assertTrue(pipeline.urlRuns.isEmpty(), "час назад при интервале 6ч — ещё рано");
        assertTrue(searchRepo.stamped.isEmpty(), "пропущенный поиск не должен получать новую отметку времени");
    }

    @Test
    void searchPastItsInterval_runsAgain() {
        profiles.jobs = List.of(job("Поиск", 7L, "оператор"));
        searchRepo.urlSearches = List.of(
            scheduled(7L, Instant.now().minus(Duration.ofHours(7)).toString(), 6));

        runAllTasks();

        assertEquals(List.of("Поиск"), pipeline.urlRuns);
    }

    @Test
    void malformedLastRunAt_treatedAsDue_ratherThanStallingForever() {
        // Fail-open: испорченная метка времени не должна навсегда заморозить поиск.
        profiles.jobs = List.of(job("Поиск", 7L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(7L, "не-дата", 6));

        runAllTasks();

        assertEquals(List.of("Поиск"), pipeline.urlRuns);
    }

    // ── изоляция ошибок ──

    @Test
    void oneFailingSearch_doesNotStopTheRest() {
        pipeline.failOn.add("Сломанный");
        profiles.jobs = List.of(
            job("Первый", 1L, "оператор"), job("Сломанный", 2L, "оператор"), job("Третий", 3L, "оператор"));

        runAllTasks();

        assertEquals(List.of("Первый", "Сломанный", "Третий"), pipeline.fullRuns,
            "падение одного поиска не должно отменять остальные");
    }

    @Test
    void failingUrlSearch_stillStampsLastRunAt() {
        // Иначе сломанный поиск повторялся бы на каждом пятиминутном тике вместо
        // одного раза за свой интервал — то есть долбил бы внешний сервис.
        pipeline.failOn.add("Сломанный");
        profiles.jobs = List.of(job("Сломанный", 7L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(7L, null, 6));

        runAllTasks();

        assertEquals(List.of(7L), searchRepo.stamped, "отметка времени ставится и при ошибке");
    }

    @Test
    void lockSkippedUrlSearch_doesNotStampLastRunAt() {
        // Регрессия: поиск по ссылке и обычный HH-пайплайн для того же person+searchName
        // делят одну блокировку. Раньше last_run_at штамповался даже когда прогон
        // реально не состоялся из-за этой блокировки — следующая попытка откладывалась
        // на полный run_interval_hours (часы) вместо ближайшего пятиминутного тика.
        pipeline.skipOn.add("Занят");
        profiles.jobs = List.of(job("Занят", 7L, "оператор"));
        searchRepo.urlSearches = List.of(scheduled(7L, null, 6));

        runAllTasks();

        assertEquals(List.of("Занят"), pipeline.urlRuns, "попытка запуска всё равно происходит");
        assertTrue(searchRepo.stamped.isEmpty(), "пропуск из-за блокировки не должен откладывать следующую попытку на весь интервал");
    }

    @Test
    void lockSkippedTelegramSearch_doesNotStampLastRunAt() {
        pipeline.skipOn.add("Занят");
        profiles.jobs = List.of(job("Занят", 7L, "оператор"));
        searchRepo.telegramSearches = List.of(scheduled(7L, null, 6));

        runAllTasks();

        assertEquals(List.of("Занят"), pipeline.telegramRuns, "попытка запуска всё равно происходит");
        assertTrue(searchRepo.stamped.isEmpty(), "пропуск из-за блокировки не должен откладывать следующую попытку на весь интервал");
    }

    @Test
    void aTaskThrowing_neverEscapesToTheScheduler() {
        // Исключение, вылетевшее из задачи, отменяет её ПОВТОРНЫЕ запуски в Spring —
        // то есть один сбой навсегда убил бы этот триггер.
        FakePublisher exploding = new FakePublisher() {
            @Override public void publishDueQueued(int limit) { throw new IllegalStateException("бум"); }
            @Override public void publishDueDelayed(int limit) { throw new IllegalStateException("бум"); }
        };
        scheduler = new PipelineScheduler(pipeline, profiles, config, analyzer, searchRepo,
            freeModels, flags, schema, subscriptions, exploding, engagement, vacancyRepo);

        assertDoesNotThrow(this::runAllTasks);
    }

    // ── триггеры читают настройки на каждом срабатывании ──

    @Test
    void pipelineTrigger_rereadsIntervalFromSettings_notFrozenAtStartup() {
        // Ради этого триггеры и заданы функциями вместо @Scheduled: смена интервала
        // в настройках должна действовать без перезапуска приложения.
        Trigger trigger = tasks().get(0).getTrigger();
        Instant completed = Instant.parse("2026-08-16T10:00:00Z");
        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(completed, completed, completed);

        config.setPipelineIntervalMs(600_000);
        Instant afterTenMinutes = trigger.nextExecution(context);
        config.setPipelineIntervalMs(1_800_000);
        Instant afterThirtyMinutes = trigger.nextExecution(context);

        assertEquals(completed.plusMillis(600_000), afterTenMinutes);
        assertEquals(completed.plusMillis(1_800_000), afterThirtyMinutes,
            "новый интервал должен применяться сразу, а не после рестарта");
    }

    @Test
    void dailyTrigger_usesCronFromSettings() {
        Trigger trigger = tasks().get(1).getTrigger();
        config.setDailyCron("0 0 6 * * *");
        SimpleTriggerContext context = new SimpleTriggerContext();
        Instant completed = Instant.parse("2026-08-16T10:00:00Z");
        context.update(completed, completed, completed);

        Instant next = trigger.nextExecution(context);

        assertNotNull(next, "cron из настроек должен давать следующее срабатывание");
        assertTrue(next.isAfter(completed));
    }

    @Test
    void subscriberTrigger_firstExecutionIsDelayedPastTheSchemaMigrationWindow() {
        // Регрессия: БЕЗ initialDelay первое срабатывание PeriodicTrigger происходит
        // немедленно при регистрации (см. PeriodicTrigger.nextExecution: при пустом
        // TriggerContext, если initialDelay не задан, возвращается clock.instant() —
        // то есть "сейчас") — то есть до того, как SchemaMigrator успевает завершиться
        // при старте. Этот самый первый тик молча пропускается schemaNotReady(), а
        // Spring планирует СЛЕДУЮЩИЙ через полный SUBSCRIBER_COUNT_CHECK_INTERVAL (6ч)
        // от него, а не скоро — на каждом рестарте (а деплоев в день много) подписчики/
        // просмотры/реакции в Grafana показывали "No data" до 6 часов. Проверяем, что
        // первое срабатывание сдвинуто на initialDelay (~5 мин), а не на "сейчас".
        Trigger trigger = tasks().get(8).getTrigger();
        Instant now = Instant.now();
        SimpleTriggerContext freshContext = new SimpleTriggerContext(); // как сразу после регистрации, ничего ещё не выполнялось

        Instant firstExecution = trigger.nextExecution(freshContext);

        assertTrue(firstExecution.isAfter(now.plus(Duration.ofMinutes(2))),
            "первое срабатывание должно быть отложено на initialDelay, а не происходить немедленно ('сейчас' попадает точно в окно миграции схемы при старте)");
        assertTrue(firstExecution.isBefore(now.plus(Duration.ofMinutes(10))),
            "и не отложено на полный 6-часовой интервал");
    }
}
