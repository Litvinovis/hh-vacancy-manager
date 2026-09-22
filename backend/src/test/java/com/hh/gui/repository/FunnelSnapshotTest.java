package com.hh.gui.repository;

import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Этапы воронки не должны пересекаться и обязаны покрывать все строки — иначе панель
 * «где сейчас затор» будет врать тем убедительнее, чем больше в базе вакансий.
 */
@SpringBootTest
@ActiveProfiles("test")
class FunnelSnapshotTest {

    @Autowired
    private VacancyRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private int seq;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM vacancies");
        seq = 0;
    }

    private Vacancy saved(String verdict, String scrapeStatus, boolean notified) {
        return saved(verdict, scrapeStatus, notified, 0);
    }

    private Vacancy saved(String verdict, String scrapeStatus, boolean notified, int score) {
        Vacancy v = new Vacancy();
        v.setAiScore(score);
        v.setHhId("f" + (++seq));
        v.setTitle("Вакансия " + seq);
        v.setPerson("Все пользователи");
        v.setSearchName("Общая удалёнка");
        v.setAiVerdict(verdict);
        v.setScrapeStatus(scrapeStatus);
        v.setNotified(notified);
        repo.save(v);
        return v;
    }

    @Test
    void everyRowLandsInExactlyOneStage() {
        saved("pending", "pending", false);   // ждёт скрейпа
        saved("pending", "failed", false);    // скрейп не удался
        saved("pending", "ok", false);        // ждёт анализа
        saved("no", "ok", false);             // отклонено
        saved("fraud", "ok", false);          // обман
        saved("yes", "ok", false);            // одобрено, ещё не отправлено
        saved("yes", "ok", true);             // опубликовано

        Map<String, Integer> funnel = repo.funnelSnapshot(0);

        assertEquals(1, funnel.getOrDefault("awaiting_scrape", 0));
        assertEquals(1, funnel.getOrDefault("scrape_failed", 0));
        assertEquals(1, funnel.getOrDefault("awaiting_ai", 0));
        assertEquals(1, funnel.getOrDefault("rejected", 0));
        assertEquals(1, funnel.getOrDefault("fraud", 0));
        assertEquals(1, funnel.getOrDefault("approved_queued", 0));
        assertEquals(1, funnel.getOrDefault("published", 0));
        assertEquals(7, funnel.values().stream().mapToInt(Integer::intValue).sum(),
            "сумма по этапам должна совпадать с числом строк — иначе этапы пересекаются или не покрывают всё");
    }

    @Test
    void emptyStages_areReportedAsZeroSoTheGaugeAlwaysExists() {
        // После рестарта ряд vacancies_funnel_stage{stage="awaiting_ai"} появлялся только с первой
        // вакансией на этом этапе — до того панель показывала «No data» вместо 0 (22.09.2026).
        saved("yes", "ok", true);

        Map<String, Integer> funnel = repo.funnelSnapshot(0);

        assertEquals(VacancyRepository.FUNNEL_STAGES.size(), funnel.size(), "все этапы, даже пустые");
        assertEquals(0, funnel.get("awaiting_ai"));
        assertEquals(0, funnel.get("awaiting_scrape"));
        assertEquals(1, funnel.get("published"));
    }

    @Test
    void approvedBelowChannelFloor_isNotCountedAsWaitingForPublication() {
        // 22.09.2026: канал берёт топ по скору, и одобренные с 60–79 «ждали» вечно —
        // 752 в панели при ~200 реальных кандидатах. Планка делит их на два этапа.
        saved("yes", "ok", false, 85);
        saved("yes", "ok", false, 72);

        Map<String, Integer> funnel = repo.funnelSnapshot(80);

        assertEquals(1, funnel.get("approved_queued"));
        assertEquals(1, funnel.get("approved_below_channel"));
        assertEquals(0, funnel.get("published"));
    }
}
