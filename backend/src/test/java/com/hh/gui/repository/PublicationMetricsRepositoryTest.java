package com.hh.gui.repository;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicationMetricsRepositoryTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void combinesPublicationDestinationsAndKeepsTheSameTelegramCohort(String database) {
        String url = database.equals("h2") ? "jdbc:h2:mem:pub_metrics;DB_CLOSE_DELAY=-1"
            : "jdbc:sqlite:" + temp.resolve("metrics.db");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.execute("CREATE TABLE searches (id INT PRIMARY KEY, search_kind VARCHAR(20))");
        jdbc.execute("""
            CREATE TABLE vacancies (hh_id VARCHAR(100), source VARCHAR(20), search_id INT, ai_verdict VARCHAR(10),
                created_at VARCHAR(40), channel_published_at VARCHAR(40), vk_post_id VARCHAR(20), vk_published_at VARCHAR(40))
            """);
        jdbc.execute("CREATE TABLE vk_articles (vk_post_id VARCHAR(20), published_at VARCHAR(40))");
        jdbc.update("INSERT INTO searches VALUES (1, 'EDITORIAL'), (2, 'PERSONAL')");
        String day = "2026-11-20T12:00:00";
        String week = "2026-11-14T12:00:00";
        String today = "2026-11-21T10:00:00.000Z";
        String earlier = "2026-11-18T10:00:00Z";
        String old = "2026-11-13T10:00:00Z";
        jdbc.update("INSERT INTO vacancies VALUES (?, 'telegram', 1, 'yes', ?, ?, '123', ?)",
            "tg_call_rabota_1", earlier, today, today);
        jdbc.update("INSERT INTO vacancies VALUES (?, 'hh', 1, 'yes', ?, ?, '123', ?)", "1", earlier, today, today);
        jdbc.update("INSERT INTO vacancies VALUES (?, 'telegram', 1, 'no', ?, NULL, NULL, NULL)",
            "tg_call_rabota_2", earlier);
        jdbc.update("INSERT INTO vacancies VALUES (?, 'telegram', 1, 'yes', ?, ?, '124', ?)",
            "tg_call_rabota_3", old, earlier, earlier);
        jdbc.update("INSERT INTO vacancies VALUES (?, 'telegram', 2, 'yes', ?, NULL, NULL, NULL)",
            "tg_call_rabota_4", earlier);
        jdbc.update("INSERT INTO vk_articles VALUES ('125', ?), ('126', ?), (NULL, ?)", today, old, today);

        var repo = new PublicationMetricsRepository(jdbc);
        assertEquals(new PublicationMetricsRepository.PublicationCounts(2, 3, 2, 3), repo.countSince(day, week));
        var cohort = repo.editorialTelegramSince(week);
        assertEquals(2, cohort.size());
        assertEquals(2, cohort.stream().filter(List.of(
            new PublicationMetricsRepository.TelegramCandidate("tg_call_rabota_1", "yes", true, true),
            new PublicationMetricsRepository.TelegramCandidate("tg_call_rabota_2", "no", false, false)
        )::contains).count());
    }
}
