package com.hh.gui.repository;

import com.hh.gui.model.VkArticle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

@Repository
public class VkArticleRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<VkArticle> MAPPER = (rs, i) -> {
        VkArticle a = new VkArticle();
        a.setId(rs.getLong("id"));
        a.setTopicKey(rs.getString("topic_key"));
        a.setKind(rs.getString("kind"));
        a.setTitle(rs.getString("title"));
        a.setBody(rs.getString("body"));
        a.setPollOptions(rs.getString("poll_options"));
        a.setStatus(rs.getString("status"));
        a.setPlannedFor(rs.getString("planned_for"));
        a.setGeneratedAt(rs.getString("generated_at"));
        a.setPublishedAt(rs.getString("published_at"));
        a.setVkPostId(rs.getString("vk_post_id"));
        a.setCreatedAt(rs.getString("created_at"));
        return a;
    };

    public VkArticleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VkArticle save(VkArticle a) {
        String now = Instant.now().toString();
        if (a.getCreatedAt() == null) a.setCreatedAt(now);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO vk_articles (topic_key, kind, title, body, poll_options, status, planned_for, " +
                "generated_at, published_at, vk_post_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, a.getTopicKey()); ps.setString(2, a.getKind()); ps.setString(3, a.getTitle());
            ps.setString(4, a.getBody()); ps.setString(5, a.getPollOptions()); ps.setString(6, a.getStatus());
            ps.setString(7, a.getPlannedFor()); ps.setString(8, a.getGeneratedAt());
            ps.setString(9, a.getPublishedAt()); ps.setString(10, a.getVkPostId()); ps.setString(11, a.getCreatedAt());
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key != null) a.setId(key.longValue());
        return a;
    }

    public void update(VkArticle a) {
        jdbc.update("UPDATE vk_articles SET title=?, body=?, poll_options=?, status=?, generated_at=?, " +
            "published_at=?, vk_post_id=? WHERE id=?",
            a.getTitle(), a.getBody(), a.getPollOptions(), a.getStatus(), a.getGeneratedAt(),
            a.getPublishedAt(), a.getVkPostId(), a.getId());
    }

    /** Темы, публиковавшиеся или запланированные после даты — их планировщик не повторяет. */
    public Set<String> topicsUsedSince(String sinceDate) {
        return new HashSet<>(jdbc.queryForList(
            "SELECT DISTINCT topic_key FROM vk_articles WHERE planned_for >= ? AND status <> 'failed'",
            String.class, sinceDate));
    }

    /** Есть ли уже план на неделю, начинающуюся с даты (защита от двойного планирования). */
    public int countPlannedFrom(String fromDate) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vk_articles WHERE planned_for >= ?", Integer.class, fromDate);
        return n != null ? n : 0;
    }

    /** Запланированные без текста на дату и раньше — их надо сгенерировать. */
    public List<VkArticle> findToGenerate(String upToDate) {
        return jdbc.query("SELECT * FROM vk_articles WHERE status='planned' AND planned_for <= ? ORDER BY planned_for",
            MAPPER, upToDate);
    }

    /** Готовые к публикации на дату и раньше (просроченные — тоже, порядок по дате). */
    public Optional<VkArticle> nextToPublish(String upToDate) {
        List<VkArticle> rows = jdbc.query(
            "SELECT * FROM vk_articles WHERE status='generated' AND planned_for <= ? ORDER BY planned_for LIMIT 1",
            MAPPER, upToDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Опубликованные статьи и опросы с момента (ISO). Очередь VK считает их вместе с
     * вакансиями: статья занимает пост окна и держит паузу до следующего так же, как вакансия.
     */
    public int countPublishedSince(String sinceIso) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vk_articles WHERE status='published' AND published_at >= ?", Integer.class, sinceIso);
        return n != null ? n : 0;
    }

    public String lastPublishedAt() {
        return jdbc.query("SELECT MAX(published_at) AS t FROM vk_articles WHERE status='published'",
            rs -> rs.next() ? rs.getString("t") : null);
    }

    public List<VkArticle> findAll(int limit) {
        return jdbc.query("SELECT * FROM vk_articles ORDER BY planned_for DESC, id DESC LIMIT ?", MAPPER, limit);
    }
}
