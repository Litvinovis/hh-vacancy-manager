package com.hh.gui.ai;

import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Метрики по моделям для Grafana (24.09.2026).
 *
 * Цепочка из трёх бесплатных моделей отвечает вперемешку, а планка канала одна на всех.
 * Если одна модель ставит в среднем 85, а другая 70, в канал уходит почти всё от первой —
 * это видно только в разрезе по моделям:
 * <ul>
 *   <li>ai_model_score_avg / ai_model_approved_ratio / ai_model_analyzed — по вакансиям за 7 дней,
 *       которые модель оценивала сама (колонка ai_model);</li>
 *   <li>ai_model_failure_ratio — доля сломанных или неполных ответов в последних вызовах
 *       (ModelHealth); по ней FreeModelUpdater убирает модель из цепочки.</li>
 * </ul>
 */
@Component
public class ModelMetrics {

    private static final Logger log = LoggerFactory.getLogger(ModelMetrics.class);
    static final Duration WINDOW = Duration.ofDays(7);

    private final VacancyRepository vacancyRepo;
    private final ModelHealth health;
    private final MultiGauge scoreAvg;
    private final MultiGauge approvedRatio;
    private final MultiGauge analyzed;
    private final MultiGauge failureRatio;

    public ModelMetrics(VacancyRepository vacancyRepo, ModelHealth health, MeterRegistry registry) {
        this.vacancyRepo = vacancyRepo;
        this.health = health;
        this.scoreAvg = MultiGauge.builder("ai_model_score_avg").tag("application", "hh-gui")
            .description("Средний AI-скор вакансий, оценённых моделью, за 7 дней").register(registry);
        this.approvedRatio = MultiGauge.builder("ai_model_approved_ratio").tag("application", "hh-gui")
            .description("Доля одобренных моделью вакансий за 7 дней").register(registry);
        this.analyzed = MultiGauge.builder("ai_model_analyzed").tag("application", "hh-gui")
            .description("Вакансий, оценённых моделью, за 7 дней").register(registry);
        this.failureRatio = MultiGauge.builder("ai_model_failure_ratio").tag("application", "hh-gui")
            .description("Доля сломанных или неполных ответов модели в последних вызовах").register(registry);
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT10M")
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> score = new ArrayList<>();
            List<MultiGauge.Row<?>> approved = new ArrayList<>();
            List<MultiGauge.Row<?>> count = new ArrayList<>();
            for (VacancyRepository.ModelScoreStats s : vacancyRepo.modelScoreStats(Instant.now().minus(WINDOW).toString())) {
                Tags tags = Tags.of("model", s.model());
                score.add(MultiGauge.Row.of(tags, s.avgScore()));
                approved.add(MultiGauge.Row.of(tags, s.approvedShare()));
                count.add(MultiGauge.Row.of(tags, s.analyzed()));
            }
            scoreAvg.register(score, true);
            approvedRatio.register(approved, true);
            analyzed.register(count, true);

            List<MultiGauge.Row<?>> failures = new ArrayList<>();
            health.snapshot().keySet().forEach(model -> {
                Double rate = health.failureRate(model);
                if (rate != null) failures.add(MultiGauge.Row.of(Tags.of("model", model), rate));
            });
            failureRatio.register(failures, true);
        } catch (Exception e) {
            // Схема может быть ещё не готова в первые минуты после старта — следующий тик повторит.
            log.debug("Метрики по моделям не обновлены: {}", e.getMessage());
        }
    }
}
