package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;

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

    private final VacancyPipelineService pipelineService;
    private final SearchProfileFactory profileFactory;
    private final RuntimeConfig runtimeConfig;
    private final VacancyAiAnalyzer aiAnalyzer;

    public PipelineScheduler(VacancyPipelineService pipelineService, SearchProfileFactory profileFactory,
                              RuntimeConfig runtimeConfig, VacancyAiAnalyzer aiAnalyzer) {
        this.pipelineService = pipelineService;
        this.profileFactory = profileFactory;
        this.runtimeConfig = runtimeConfig;
        this.aiAnalyzer = aiAnalyzer;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::runPipeline, this::nextPipelineExecution);
        registrar.addTriggerTask(this::runDailyAnalysis, this::nextDailyExecution);
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
        try {
            VacancyPipelineService.PipelineResult result = pipelineService.runFullPipeline(profileFactory.build());
            log.info("Пайплайн завершён: собрано={}, новых={}, проанализировано={}, одобрено={}",
                result.collected, result.newVacancies, result.analyzed, result.approved);
        } catch (Exception e) {
            log.error("Запланированный пайплайн завершился ошибкой: {}", e.getMessage(), e);
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
        try {
            int analyzed = pipelineService.analyzeAllPending(profileFactory.build());
            log.info("=== Конец ежедневного анализа необработанных: {} вакансий проанализировано ===", analyzed);
        } catch (Exception e) {
            log.error("Ежедневный анализ необработанных завершился ошибкой: {}", e.getMessage(), e);
        }
    }
}
