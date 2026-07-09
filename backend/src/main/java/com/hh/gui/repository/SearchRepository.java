package com.hh.gui.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.model.SearchConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class SearchRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RowMapper<SearchConfig> rowMapper = (rs, rowNum) -> {
        SearchConfig s = new SearchConfig();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getLong("user_id"));
        s.setName(rs.getString("name"));
        s.setQueries(readList(rs.getString("queries")));
        s.setArea(rs.getInt("area"));
        s.setSchedule(rs.getString("schedule"));
        s.setSalaryMin(rs.getInt("salary_min"));
        s.setPriorityDistricts(readList(rs.getString("priority_districts")));
        s.setSkills(readList(rs.getString("skills")));
        s.setNotSuitable(readList(rs.getString("not_suitable")));
        s.setExcludeWords(readList(rs.getString("exclude_words")));
        s.setAiNotes(rs.getString("ai_notes"));
        s.setEnabled(rs.getInt("enabled") == 1);
        s.setCreatedAt(rs.getString("created_at"));
        s.setUpdatedAt(rs.getString("updated_at"));
        s.setGlobal(rs.getInt("is_global") == 1);
        s.setSourceUrl(rs.getString("source_url"));
        int runIntervalHours = rs.getInt("run_interval_hours");
        s.setRunIntervalHours(rs.wasNull() ? null : runIntervalHours);
        s.setLastRunAt(rs.getString("last_run_at"));
        return s;
    };

    @Autowired
    public SearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String writeList(List<String> list) {
        try {
            return mapper.writeValueAsString(list != null ? list : Collections.emptyList());
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Excludes is_global searches — they're admin-managed and don't count against a user's personal cap. */
    public long countByUserId(Long userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM searches WHERE user_id = ? AND is_global = FALSE", Integer.class, userId);
        return count != null ? count : 0;
    }

    public SearchConfig save(SearchConfig s) {
        String now = Instant.now().toString();
        if (s.getCreatedAt() == null || s.getCreatedAt().isEmpty()) {
            s.setCreatedAt(now);
        }
        s.setUpdatedAt(now);

        String sql = """
            INSERT INTO searches (user_id, name, queries, area, schedule, salary_min,
                priority_districts, skills, not_suitable, exclude_words, ai_notes, enabled,
                created_at, updated_at, is_global, source_url, run_interval_hours, last_run_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, s.getUserId());
            ps.setString(i++, s.getName());
            ps.setString(i++, writeList(s.getQueries()));
            ps.setInt(i++, s.getArea());
            ps.setString(i++, s.getSchedule());
            ps.setInt(i++, s.getSalaryMin());
            ps.setString(i++, writeList(s.getPriorityDistricts()));
            ps.setString(i++, writeList(s.getSkills()));
            ps.setString(i++, writeList(s.getNotSuitable()));
            ps.setString(i++, writeList(s.getExcludeWords()));
            ps.setString(i++, s.getAiNotes());
            ps.setInt(i++, s.isEnabled() ? 1 : 0);
            ps.setString(i++, s.getCreatedAt());
            ps.setString(i++, s.getUpdatedAt());
            ps.setInt(i++, s.isGlobal() ? 1 : 0);
            ps.setString(i++, s.getSourceUrl());
            ps.setObject(i++, s.getRunIntervalHours(), Types.INTEGER);
            ps.setString(i, s.getLastRunAt());
            return ps;
        }, keyHolder);

        s.setId(keyHolder.getKey().longValue());
        return s;
    }

    public void update(SearchConfig s) {
        String now = Instant.now().toString();
        s.setUpdatedAt(now);

        jdbc.update(
            "UPDATE searches SET name=?, queries=?, area=?, schedule=?, salary_min=?, " +
            "priority_districts=?, skills=?, not_suitable=?, exclude_words=?, ai_notes=?, enabled=?, updated_at=?, " +
            "source_url=?, run_interval_hours=? " +
            "WHERE id=?",
            s.getName(), writeList(s.getQueries()), s.getArea(), s.getSchedule(), s.getSalaryMin(),
            writeList(s.getPriorityDistricts()), writeList(s.getSkills()), writeList(s.getNotSuitable()),
            writeList(s.getExcludeWords()), s.getAiNotes(), s.isEnabled() ? 1 : 0, s.getUpdatedAt(),
            s.getSourceUrl(), s.getRunIntervalHours(), s.getId());
    }

    /** Stamps the last automatic/manual run time for a search, used by the per-search interval scheduler. */
    public void updateLastRunAt(Long id, String lastRunAt) {
        jdbc.update("UPDATE searches SET last_run_at=? WHERE id=?", lastRunAt, id);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM searches WHERE id=?", id);
    }

    /**
     * SQLite doesn't enforce FK constraints unless "PRAGMA foreign_keys=ON" is
     * set per-connection (it isn't, here), so the schema's ON DELETE CASCADE on
     * searches.user_id is decorative — deleting a user must clean up their
     * searches explicitly. See UserService.delete().
     *
     * Excludes is_global searches: user_id on those is just "which admin manages
     * it", not ownership of the shared result — deleting that admin's account
     * shouldn't wipe out a search every user relies on.
     */
    public void deleteByUserId(Long userId) {
        jdbc.update("DELETE FROM searches WHERE user_id=? AND is_global = FALSE", userId);
    }

    public Optional<SearchConfig> findById(Long id) {
        List<SearchConfig> results = jdbc.query("SELECT * FROM searches WHERE id = ?", rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<SearchConfig> findByUserId(Long userId) {
        return jdbc.query("SELECT * FROM searches WHERE user_id = ? ORDER BY id", rowMapper, userId);
    }

    public List<SearchConfig> findAllEnabled() {
        return jdbc.query("SELECT * FROM searches WHERE enabled = 1 ORDER BY user_id, id", rowMapper);
    }

    public List<SearchConfig> findAllGlobal() {
        return jdbc.query("SELECT * FROM searches WHERE is_global = TRUE ORDER BY id", rowMapper);
    }

    /**
     * Enabled searches configured for scheduled URL-based runs (source_url +
     * run_interval_hours both set) — candidates for PipelineScheduler's per-search
     * interval trigger. Due-time comparison (last_run_at + interval vs now) happens
     * in Java, not SQL, to avoid SQLite/H2 date-arithmetic differences.
     */
    public List<SearchConfig> findScheduledUrlSearches() {
        return jdbc.query(
            "SELECT * FROM searches WHERE enabled = TRUE AND source_url IS NOT NULL AND source_url != '' " +
            "AND run_interval_hours IS NOT NULL ORDER BY id",
            rowMapper);
    }
}
