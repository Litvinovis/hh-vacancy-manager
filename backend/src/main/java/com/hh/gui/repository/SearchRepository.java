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

    public long countByUserId(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM searches WHERE user_id = ?", Integer.class, userId);
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
                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setString(i, s.getUpdatedAt());
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
            "priority_districts=?, skills=?, not_suitable=?, exclude_words=?, ai_notes=?, enabled=?, updated_at=? " +
            "WHERE id=?",
            s.getName(), writeList(s.getQueries()), s.getArea(), s.getSchedule(), s.getSalaryMin(),
            writeList(s.getPriorityDistricts()), writeList(s.getSkills()), writeList(s.getNotSuitable()),
            writeList(s.getExcludeWords()), s.getAiNotes(), s.isEnabled() ? 1 : 0, s.getUpdatedAt(), s.getId());
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM searches WHERE id=?", id);
    }

    /**
     * SQLite doesn't enforce FK constraints unless "PRAGMA foreign_keys=ON" is
     * set per-connection (it isn't, here), so the schema's ON DELETE CASCADE on
     * searches.user_id is decorative — deleting a user must clean up their
     * searches explicitly. See UserService.delete().
     */
    public void deleteByUserId(Long userId) {
        jdbc.update("DELETE FROM searches WHERE user_id=?", userId);
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
}
