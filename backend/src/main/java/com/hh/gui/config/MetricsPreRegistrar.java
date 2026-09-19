package com.hh.gui.config;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.ai.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Регистрирует счётчики нулями при старте.
 *
 * Micrometer создаёт счётчик в момент первого increment — до него метрики в выдаче просто
 * нет, и Prometheus не отдаёт по ней ничего. На дашборде это выглядит как «нет данных»,
 * неотличимо от поломки сбора: 18.09.2026 половина панелей «Telegram Vacancy Source»
 * пустовала именно поэтому, а не из-за отсутствия событий. Дополнительный эффект — у
 * increase()/rate() появляется точка отсчёта: без неё первый всплеск на свежесозданной
 * серии не виден (тот же случай, что уже лечился preRegisterChannel для каналов).
 *
 * Регистрируются только комбинации меток с заранее известным набором значений. Метки,
 * которые приходят из данных (статусы HTTP скрейпера, причины отказов провайдера),
 * не выдумываются: лучше честное отсутствие серии, чем выдуманный ноль по метке,
 * которой, возможно, никогда не будет.
 */
@Component
public class MetricsPreRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetricsPreRegistrar.class);

    private final AiMetrics aiMetrics;

    public MetricsPreRegistrar(AiMetrics aiMetrics) {
        this.aiMetrics = aiMetrics;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Только счётчики: ни одного обращения к БД. ApplicationRunner может отработать
        // раньше, чем SchemaMigrator доведёт схему (та же гонка, от которой защищается
        // PipelineScheduler.schemaNotReady), поэтому каналы-источники предрегистрируются
        // из планировщика, где эта защита уже есть.
        aiMetrics.preRegisterCounters();
        log.info("Счётчики пайплайна предзарегистрированы нулями");
    }
}
