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
        Vacancy v = new Vacancy();
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

        Map<String, Integer> funnel = repo.funnelSnapshot();

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
}
