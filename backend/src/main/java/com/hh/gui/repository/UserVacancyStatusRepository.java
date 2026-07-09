package com.hh.gui.repository;

import com.hh.gui.model.UserVacancyStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class UserVacancyStatusRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<UserVacancyStatus> rowMapper = (rs, rowNum) -> {
        UserVacancyStatus s = new UserVacancyStatus();
        s.setUserId(rs.getLong("user_id"));
        s.setVacancyId(rs.getLong("vacancy_id"));
        s.setStatus(rs.getString("status"));
        s.setRejectionReason(rs.getString("rejection_reason"));
        s.setNotes(rs.getString("notes"));
        s.setAppliedAt(rs.getString("applied_at"));
        s.setUpdatedAt(rs.getString("updated_at"));
        return s;
    };

    public UserVacancyStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserVacancyStatus> findOne(Long userId, Long vacancyId) {
        List<UserVacancyStatus> results = jdbc.query(
            "SELECT * FROM user_vacancy_status WHERE user_id = ? AND vacancy_id = ?",
            rowMapper, userId, vacancyId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Keyed by vacancy_id — used to overlay a page of vacancies for one viewer in one round-trip. */
    public Map<Long, UserVacancyStatus> findByUserAndVacancyIds(Long userId, List<Long> vacancyIds) {
        if (vacancyIds == null || vacancyIds.isEmpty()) return Map.of();
        String placeholders = vacancyIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] params = new Object[vacancyIds.size() + 1];
        params[0] = userId;
        for (int i = 0; i < vacancyIds.size(); i++) params[i + 1] = vacancyIds.get(i);

        List<UserVacancyStatus> results = jdbc.query(
            "SELECT * FROM user_vacancy_status WHERE user_id = ? AND vacancy_id IN (" + placeholders + ")",
            rowMapper, params);
        Map<Long, UserVacancyStatus> byVacancyId = new LinkedHashMap<>();
        for (UserVacancyStatus s : results) byVacancyId.put(s.getVacancyId(), s);
        return byVacancyId;
    }

    /** Classic update-then-insert-if-missing upsert — avoids relying on SQLite/H2-specific ON CONFLICT syntax. */
    public void upsertStatus(Long userId, Long vacancyId, String status, String rejectionReason, String appliedAt) {
        String now = Instant.now().toString();
        int updated = jdbc.update(
            "UPDATE user_vacancy_status SET status=?, rejection_reason=?, applied_at=?, updated_at=? " +
            "WHERE user_id=? AND vacancy_id=?",
            status, rejectionReason, appliedAt, now, userId, vacancyId);
        if (updated == 0) {
            jdbc.update(
                "INSERT INTO user_vacancy_status (user_id, vacancy_id, status, rejection_reason, applied_at, notes, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, '', ?)",
                userId, vacancyId, status, rejectionReason, appliedAt, now);
        }
    }
}
