package com.hh.gui.service;

import com.hh.gui.ai.FreeModelUpdater;
import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Single source of scheduled pipeline execution.
 * Replaces the previous two independent @Scheduled methods (one with a hardcoded
 * 10-minute interval and hardcoded search profile, one with a 2-hour cron and
 * YAML-based profile) that ran concurrently against different configs.
 *
 * Both triggers re-read RuntimeConfig on every firing, so pipelineIntervalMs and
 * dailyCron settings from the runtime settings API actually take effect.
 */
@Configuration
public class PipelineScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    // How often to check whether any URL-based search is due for its own run_interval_hours —
    // independent of (and much finer-grained than) that interval itself, since interval_hours
    // is set per search and can't drive a single shared cron/rate trigger directly.
    private static final long URL_SEARCH_CHECK_INTERVAL_MS = 5 * 60 * 1000;
    // discoverFromUrl always walks every page up to this cap (see its javadoc for why
    // an early-stop-on-already-seen heuristic was tried and removed) — this is the same
    // ceiling MAX_URL_SEARCH_PAGES enforces there, just named for this call site.
    private static final int URL_SEARCH_SCHEDULED_MAX_PAGES = 10;

    // The OpenRouter ":free" pool is volatile — models get promoted to paid or removed;
    // see FreeModelUpdater. Half a day keeps the list fresh without meaningful cost
    // (one catalog GET; an LLM call only when something actually changed).
    private static final Duration FREE_MODEL_REFRESH_INTERVAL = Duration.ofHours(12);
    // Not at boot: a deploy restart mustn't burn an LLM ranking call every time, and
    // the very first scheduled run still catches a dead list within minutes.
    private static final Duration FREE_MODEL_REFRESH_INITIAL_DELAY = Duration.ofMinutes(10);

    private final VacancyPipelineService pipelineService;
    private final SearchProfileFactory profileFactory;
    private final RuntimeConfig runtimeConfig;
    private final VacancyAiAnalyzer aiAnalyzer;
    private final SearchRepository searchRepo;
    private final FreeModelUpdater freeModelUpdater;
    private final LegacyImportService legacyImportService;

    public PipelineScheduler(VacancyPipelineService pipelineService, SearchProfileFactory profileFactory,
                              RuntimeConfig runtimeConfig, VacancyAiAnalyzer aiAnalyzer, SearchRepository searchRepo,
                              FreeModelUpdater freeModelUpdater, LegacyImportService legacyImportService) {
        this.pipelineService = pipelineService;
        this.profileFactory = profileFactory;
        this.runtimeConfig = runtimeConfig;
        this.aiAnalyzer = aiAnalyzer;
        this.searchRepo = searchRepo;
        this.freeModelUpdater = freeModelUpdater;
        this.legacyImportService = legacyImportService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::runPipeline, this::nextPipelineExecution);
        registrar.addTriggerTask(this::runDailyAnalysis, this::nextDailyExecution);
        registrar.addTriggerTask(this::runDueUrlSearches, new PeriodicTrigger(Duration.ofMillis(URL_SEARCH_CHECK_INTERVAL_MS)));
        PeriodicTrigger freeModelTrigger = new PeriodicTrigger(FREE_MODEL_REFRESH_INTERVAL);
        freeModelTrigger.setInitialDelay(FREE_MODEL_REFRESH_INITIAL_DELAY);
        registrar.addTriggerTask(this::refreshFreeModels, freeModelTrigger);
        // Liveness re-checks of approved postings — a background chore that only eats
        // idle scraper capacity (see checkVacancyFreshness's own guards).
        PeriodicTrigger freshnessTrigger = new PeriodicTrigger(Duration.ofMinutes(10));
        freshnessTrigger.setInitialDelay(Duration.ofMinutes(5));
        registrar.addTriggerTask(this::runFreshnessCheck, freshnessTrigger);
        // Slow-drip re-processing of the v1 archive — one modest batch per night
        // (~200 rows) until vacancies_legacy runs dry, then a permanent no-op.
        registrar.addTriggerTask(this::runLegacyImport, new CronTrigger("0 15 4 * * *"));
    }

    private void runLegacyImport() {
        if (!runtimeConfig.isPipelineEnabled()) return;
        try {
            if (!legacyImportService.hasUnmigrated()) return;
            legacyImportService.importBatch(LegacyImportService.DAILY_BATCH, false);
        } catch (Exception e) {
            log.error("Ночной импорт legacy-архива завершился ошибкой: {}", e.getMessage(), e);
        }
    }

    private void runFreshnessCheck() {
        if (!runtimeConfig.isPipelineEnabled()) return;
        try {
            pipelineService.checkVacancyFreshness(VacancyPipelineService.FRESHNESS_BATCH_PER_TICK);
        } catch (Exception e) {
            log.error("Актуализация вакансий завершилась ошибкой: {}", e.getMessage(), e);
        }
    }

    private void refreshFreeModels() {
        try {
            freeModelUpdater.refresh();
        } catch (Exception e) {
            log.error("Плановое обновление free-моделей завершилось ошибкой: {}", e.getMessage(), e);
        }
    }

    private Instant nextPipelineExecution(TriggerContext context) {
        Instant last = context.lastCompletion();
        Instant base = last != null ? last : Instant.now();
        return base.plusMillis(runtimeConfig.getPipelineIntervalMs());
    }

    private Instant nextDailyExecution(TriggerContext context) {
        Trigger trigger = new CronTrigger(runtimeConfig.getDailyCron());
        return trigger.nextExecution(context);
    }

    private void runPipeline() {
        if (!runtimeConfig.isPipelineEnabled()) {
            log.debug("Автозапуск пайплайна отключён в настройках");
            return;
        }
        if (aiAnalyzer.isRateLimited()) {
            log.info("Запланированный пайплайн пропущен — активен период охлаждения");
            return;
        }
        log.info("=== Начало запланированного пайплайна ===");
        // URL-only searches (sourceUrl set, no RSS queries) are driven by runDueUrlSearches
        // on their own run_interval_hours instead — running them here too on every RSS-cycle
        // interval would just repeat discoverNew's "no queries configured" no-op.
        List<SearchJob> jobs = profileFactory.build().stream()
            .filter(j -> j.queries != null && !j.queries.isEmpty())
            .toList();
        if (jobs.isEmpty()) {
            log.warn("Ни одного активного поиска не настроено ни у одного пользователя");
        }
        for (SearchJob job : jobs) {
            try {
                // true = let small fresh AI backlogs accumulate across ticks (see shouldDeferAnalysis)
                VacancyPipelineService.PipelineResult result = pipelineService.runFullPipeline(job, true);
                log.info("Пайплайн {} · {} завершён: собрано={}, проанализировано={}, одобрено={}",
                    job.personName, job.searchName, result.collected, result.analyzed, result.approved);
            } catch (Exception e) {
                log.error("Пайплайн {} · {} завершился ошибкой: {}", job.personName, job.searchName, e.getMessage(), e);
            }
        }
        log.info("=== Конец запланированного пайплайна ===");
    }

    private void runDailyAnalysis() {
        if (!runtimeConfig.isPipelineEnabled()) {
            log.debug("Ежедневный анализ пропущен — пайплайн отключён в настройках");
            return;
        }
        if (aiAnalyzer.isRateLimited()) {
            log.info("Ежедневный анализ необработанных пропущен — активен период охлаждения");
            return;
        }
        log.info("=== Начало ежедневного анализа необработанных ===");
        for (SearchJob job : profileFactory.build()) {
            try {
                int analyzed = pipelineService.analyzeAllPending(job);
                log.info("Ежедневный анализ {} · {}: {} вакансий проанализировано", job.personName, job.searchName, analyzed);
            } catch (Exception e) {
                log.error("Ежедневный анализ {} · {} завершился ошибкой: {}", job.personName, job.searchName, e.getMessage(), e);
            }
        }
        log.info("=== Конец ежедневного анализа необработанных ===");
    }

    /**
     * Checks every search (personal or global) with a saved source_url + run_interval_hours
     * and runs the ones whose interval has elapsed since last_run_at. Independent of the
     * RSS pipeline's single global pipelineIntervalMs — each such search picks its own cadence.
     */
    private void runDueUrlSearches() {
        if (!runtimeConfig.isPipelineEnabled()) {
            log.debug("Автозапуск поисков по ссылке пропущен — пайплайн отключён в настройках");
            return;
        }
        if (aiAnalyzer.isRateLimited()) {
            log.info("Автозапуск поисков по ссылке пропущен — активен период охлаждения");
            return;
        }
        for (SearchConfig search : searchRepo.findScheduledUrlSearches()) {
            if (!isDue(search)) continue;

            Optional<SearchJob> jobOpt = profileFactory.buildForSearchId(search.getId());
            if (jobOpt.isEmpty()) continue;
            SearchJob job = jobOpt.get();

            String now = Instant.now().toString();
            try {
                log.info("=== Автозапуск поиска по ссылке: {} · {} ===", job.personName, job.searchName);
                VacancyPipelineService.PipelineResult result =
                    pipelineService.runFullPipelineFromUrl(job, job.sourceUrl, URL_SEARCH_SCHEDULED_MAX_PAGES);
                log.info("Поиск по ссылке {} · {} завершён: собрано={}, проанализировано={}, одобрено={}",
                    job.personName, job.searchName, result.collected, result.analyzed, result.approved);
            } catch (Exception e) {
                log.error("Поиск по ссылке {} · {} завершился ошибкой: {}", job.personName, job.searchName, e.getMessage(), e);
            } finally {
                // Stamped even on failure — a search whose URL/sidecar is broken shouldn't be
                // retried every 5-minute check tick, only once per its own configured interval.
                searchRepo.updateLastRunAt(search.getId(), now);
            }
        }
    }

    private boolean isDue(SearchConfig search) {
        if (search.getLastRunAt() == null || search.getLastRunAt().isBlank()) return true;
        try {
            Instant last = Instant.parse(search.getLastRunAt());
            Instant due = last.plus(Duration.ofHours(search.getRunIntervalHours()));
            return !Instant.now().isBefore(due);
        } catch (Exception e) {
            log.warn("Некорректный last_run_at у поиска id={}, считаем готовым к запуску: {}", search.getId(), e.getMessage());
            return true;
        }
    }
}
