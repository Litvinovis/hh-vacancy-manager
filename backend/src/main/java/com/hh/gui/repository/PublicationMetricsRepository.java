package com.hh.gui.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PublicationMetricsRepository {
    public record PublicationCounts(int telegramDay, int telegramWeek, int vkDay, int vkWeek) {}

    public record TelegramCandidate(String hhId, String verdict, boolean publishedInTelegram, boolean publishedInVk) {}

    private final JdbcTemplate jdbc;

    public PublicationMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Telegram хранит время отправки каждой вакансии, а подборка VK имеет общий ID поста. */
    public PublicationCounts countSince(String day, String week) {
        return jdbc.queryForObject("""
            SELECT COUNT(CASE WHEN platform = 'telegram' AND published_at >= ? THEN 1 END) AS telegram_day,
                   COUNT(CASE WHEN platform = 'telegram' THEN 1 END) AS telegram_week,
                   COUNT(CASE WHEN platform = 'vk' AND published_at >= ? THEN 1 END) AS vk_day,
                   COUNT(CASE WHEN platform = 'vk' THEN 1 END) AS vk_week
            FROM (
                SELECT 'telegram' AS platform, channel_published_at AS published_at
                FROM vacancies WHERE channel_published_at >= ?
                UNION ALL
                SELECT 'vk' AS platform, MIN(published_at) AS published_at FROM (
                    SELECT vk_post_id AS post_id, vk_published_at AS published_at
                    FROM vacancies WHERE vk_post_id IS NOT NULL AND vk_published_at >= ?
                    UNION ALL
                    SELECT vk_post_id, published_at
                    FROM vk_articles WHERE vk_post_id IS NOT NULL AND published_at >= ?
                ) vk_posts GROUP BY post_id
            ) publications
            """, (rs, row) -> new PublicationCounts(
                rs.getInt("telegram_day"), rs.getInt("telegram_week"), rs.getInt("vk_day"), rs.getInt("vk_week")),
            day, day, week, week, week);
    }

    /** Исходы только тех вакансий, которые были собраны с начала окна. */
    public List<TelegramCandidate> editorialTelegramSince(String since) {
        return jdbc.query("""
            SELECT v.hh_id, v.ai_verdict, v.channel_published_at, v.vk_published_at
            FROM vacancies v JOIN searches s ON s.id = v.search_id
            WHERE v.source = 'telegram' AND s.search_kind = 'EDITORIAL' AND v.created_at >= ?
            """, (rs, row) -> new TelegramCandidate(rs.getString("hh_id"), rs.getString("ai_verdict"),
                rs.getString("channel_published_at") != null, rs.getString("vk_published_at") != null), since);
    }
}
