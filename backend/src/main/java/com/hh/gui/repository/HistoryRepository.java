package com.hh.gui.repository;

import com.hh.gui.model.History;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class HistoryRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<History> rowMapper = (rs, rowNum) -> {
        History h = new History();
        h.setId(rs.getLong("id"));
        h.setVacancyId(rs.getLong("vacancy_id"));
        h.setAction(rs.getString("action"));
        h.setDetails(rs.getString("details"));
        h.setCreatedAt(rs.getString("created_at"));
        return h;
    };

    @Autowired
    public HistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Long vacancyId, String action, String details) {
        String now = Instant.now().toString();
        jdbc.update(
            "INSERT INTO history (vacancy_id, action, details, created_at) VALUES (?, ?, ?, ?)",
            vacancyId, action, details, now);
    }

    public void saveAll(List<Long> ids, String action, String details) {
        if (ids == null || ids.isEmpty()) return;
        String now = Instant.now().toString();
        jdbc.batchUpdate(
            "INSERT INTO history (vacancy_id, action, details, created_at) VALUES (?, ?, ?, ?)",
            ids, ids.size(),
            (ps, id) -> {
                ps.setLong(1, id);
                ps.setString(2, action);
                ps.setString(3, details);
                ps.setString(4, now);
            });
    }

    public List<History> findByVacancyId(Long vacancyId, int limit) {
        return jdbc.query(
            "SELECT * FROM history WHERE vacancy_id = ? ORDER BY created_at DESC LIMIT ?",
            rowMapper, vacancyId, limit);
    }
}
