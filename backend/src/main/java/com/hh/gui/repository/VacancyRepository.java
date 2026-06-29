package com.hh.gui.repository;

import com.hh.gui.model.Vacancy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class VacancyRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Vacancy> rowMapper;

    @Autowired
    public VacancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> {
            Vacancy v = new Vacancy();
            v.setId(rs.getLong("id"));
            v.setHhId(rs.getString("hh_id"));
            v.setTitle(rs.getString("title"));
            v.setCompany(rs.getString("company"));
            v.setSalaryFrom(rs.getInt("salary_from"));
            v.setSalaryTo(rs.getInt("salary_to"));
            v.setCurrency(rs.getString("currency"));
            v.setAddress(rs.getString("address"));
            v.setDistrict(rs.getString("district"));
            v.setUrl(rs.getString("url"));
            v.setAiScore(rs.getInt("ai_score"));
            v.setAiVerdict(rs.getString("ai_verdict"));
            v.setAiReason(rs.getString("ai_reason"));
            v.setDescription(rs.getString("description"));
            v.setStatus(rs.getString("status"));
            v.setRejectionReason(rs.getString("rejection_reason"));
            v.setNotes(rs.getString("notes"));
            v.setAppliedAt(rs.getString("applied_at"));
            v.setCreatedAt(rs.getString("created_at"));
            v.setUpdatedAt(rs.getString("updated_at"));
            v.setSource(rs.getString("source"));
            v.setSourceQuery(rs.getString("source_query"));
            v.setRemote(rs.getInt("is_remote") == 1);
            v.setNotified(rs.getInt("notified") == 1);
            v.setPublishedAt(rs.getString("published_at"));
            v.setFoundByScan(rs.getInt("found_by_scan"));
            return v;
        };
    }

    public List<Vacancy> findAll(String status, String district, Integer minSalary,
                                  Integer minScore, String search, String tag,
                                  Boolean remote, String sort, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT v.* FROM vacancies v");
        List<Object> params = new ArrayList<>();

        if (tag != null && !tag.isEmpty()) {
            sql.append(" INNER JOIN tags t ON t.vacancy_id = v.id");
            sql.append(" AND t.name = ?");
            params.add(tag);
        }

        List<String> conditions = new ArrayList<>();
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            if ("pending".equals(status)) {
                conditions.add("v.ai_verdict = 'pending'");
            } else if ("fraud".equals(status)) {
                conditions.add("v.ai_verdict = 'fraud'");
            } else {
                conditions.add("v.status = ?");
                params.add(status);
            }
        }
        if (district != null && !district.isEmpty()) {
            conditions.add("v.district LIKE ?");
            params.add("%" + district + "%");
        }
        if (minSalary != null && minSalary > 0) {
            conditions.add("(v.salary_to >= ? OR (v.salary_to = 0 AND v.salary_from >= ?))");
            params.add(minSalary);
            params.add(minSalary);
        }
        if (minScore != null && minScore > 0) {
            conditions.add("v.ai_score >= ?");
            params.add(minScore);
        }
        if (search != null && !search.isEmpty()) {
            conditions.add("(v.title LIKE ? OR v.company LIKE ? OR v.address LIKE ?)");
            String s = "%" + search + "%";
            params.add(s);
            params.add(s);
            params.add(s);
        }
        if (remote != null) {
            conditions.add("v.is_remote = ?");
            params.add(remote ? 1 : 0);
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        // Sort
        String orderBy = switch (sort != null ? sort : "score_desc") {
            case "score_asc" -> "v.ai_score ASC";
            case "salary_desc" -> "v.salary_to DESC";
            case "salary_asc" -> "v.salary_from ASC";
            case "date_desc" -> "v.created_at DESC";
            case "date_asc" -> "v.created_at ASC";
            case "title_asc" -> "v.title ASC";
            case "id_desc" -> "v.id DESC";
            default -> "v.ai_score DESC";
        };
        sql.append(" ORDER BY ").append(orderBy);
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbc.query(sql.toString(), rowMapper, params.toArray());
    }

    public int countAll(String status, String district, Integer minSalary,
                         Integer minScore, String search, String tag, Boolean remote) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM vacancies v");
        List<Object> params = new ArrayList<>();

        if (tag != null && !tag.isEmpty()) {
            sql.append(" INNER JOIN tags t ON t.vacancy_id = v.id");
            sql.append(" AND t.name = ?");
            params.add(tag);
        }

        List<String> conditions = new ArrayList<>();
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            if ("pending".equals(status)) {
                conditions.add("v.ai_verdict = 'pending'");
            } else if ("fraud".equals(status)) {
                conditions.add("v.ai_verdict = 'fraud'");
            } else {
                conditions.add("v.status = ?");
                params.add(status);
            }
        }
        if (district != null && !district.isEmpty()) {
            conditions.add("v.district LIKE ?");
            params.add("%" + district + "%");
        }
        if (minSalary != null && minSalary > 0) {
            conditions.add("(v.salary_to >= ? OR (v.salary_to = 0 AND v.salary_from >= ?))");
            params.add(minSalary);
            params.add(minSalary);
        }
        if (minScore != null && minScore > 0) {
            conditions.add("v.ai_score >= ?");
            params.add(minScore);
        }
        if (search != null && !search.isEmpty()) {
            conditions.add("(v.title LIKE ? OR v.company LIKE ? OR v.address LIKE ?)");
            String s = "%" + search + "%";
            params.add(s);
            params.add(s);
            params.add(s);
        }
        if (remote != null) {
            conditions.add("v.is_remote = ?");
            params.add(remote ? 1 : 0);
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
    }

    public Optional<Vacancy> findById(Long id) {
        List<Vacancy> results = jdbc.query(
            "SELECT * FROM vacancies WHERE id = ?", rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Vacancy save(Vacancy v) {
        String now = Instant.now().toString();
        if (v.getCreatedAt() == null || v.getCreatedAt().isEmpty()) {
            v.setCreatedAt(now);
        }
        v.setUpdatedAt(now);

        String sql = """
            INSERT INTO vacancies (hh_id, title, company, salary_from, salary_to, currency,
                address, district, url, ai_score, ai_verdict, ai_reason, description, status,
                rejection_reason, notes, applied_at, created_at, updated_at,
                source, source_query, is_remote, notified, published_at, found_by_scan)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.getHhId());
            ps.setString(2, v.getTitle());
            ps.setString(3, v.getCompany());
            ps.setInt(4, v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
            ps.setInt(5, v.getSalaryTo() != null ? v.getSalaryTo() : 0);
            ps.setString(6, v.getCurrency());
            ps.setString(7, v.getAddress());
            ps.setString(8, v.getDistrict());
            ps.setString(9, v.getUrl());
            ps.setInt(10, v.getAiScore() != null ? v.getAiScore() : 0);
            ps.setString(11, v.getAiVerdict());
            ps.setString(12, v.getAiReason());
            ps.setString(13, v.getDescription());
            ps.setString(14, v.getStatus());
            ps.setString(15, v.getRejectionReason());
            ps.setString(16, v.getNotes());
            ps.setString(17, v.getAppliedAt());
            ps.setString(18, v.getCreatedAt());
            ps.setString(19, v.getUpdatedAt());
            ps.setString(20, v.getSource());
            ps.setString(21, v.getSourceQuery());
            ps.setInt(22, v.isRemote() ? 1 : 0);
            ps.setInt(23, v.isNotified() ? 1 : 0);
            ps.setString(24, v.getPublishedAt());
            ps.setInt(25, v.getFoundByScan());
            return ps;
        }, keyHolder);

        v.setId(keyHolder.getKey().longValue());
        return v;
    }

    public void update(Vacancy v) {
        String now = Instant.now().toString();
        v.setUpdatedAt(now);

        String sql = """
            UPDATE vacancies SET hh_id=?, title=?, company=?, salary_from=?, salary_to=?,
                currency=?, address=?, district=?, url=?, ai_score=?, ai_verdict=?,
                ai_reason=?, description=?, status=?, rejection_reason=?, notes=?,
                applied_at=?, updated_at=?, source=?, source_query=?, is_remote=?,
                notified=?, published_at=?, found_by_scan=?
            WHERE id=?
            """;

        jdbc.update(sql,
            v.getHhId(), v.getTitle(), v.getCompany(),
            v.getSalaryFrom() != null ? v.getSalaryFrom() : 0,
            v.getSalaryTo() != null ? v.getSalaryTo() : 0,
            v.getCurrency(), v.getAddress(), v.getDistrict(), v.getUrl(),
            v.getAiScore() != null ? v.getAiScore() : 0,
            v.getAiVerdict(), v.getAiReason(), v.getDescription(),
            v.getStatus(), v.getRejectionReason(), v.getNotes(),
            v.getAppliedAt(), v.getUpdatedAt(),
            v.getSource(), v.getSourceQuery(),
            v.isRemote() ? 1 : 0,
            v.isNotified() ? 1 : 0,
            v.getPublishedAt(), v.getFoundByScan(),
            v.getId());
    }

    /**
     * Update AI analysis result for a vacancy.
     */
    public void updateAiResult(String hhId, int score, String verdict, String reason) {
        String now = Instant.now().toString();
        jdbc.update(
            "UPDATE vacancies SET ai_score=?, ai_verdict=?, ai_reason=?, updated_at=? WHERE hh_id=?",
            score, verdict, reason, now, hhId);
    }

    /**
     * Reset AI score for a specific vacancy (mark for re-analysis).
     */
    public void resetScore(Long id) {
        String now = Instant.now().toString();
        jdbc.update("UPDATE vacancies SET ai_verdict='pending', ai_score=0, ai_reason='', updated_at=? WHERE id=?",
            now, id);
    }

    /**
     * Mark vacancies as notified (sent to Telegram).
     */
    public void markNotified(List<String> hhIds) {
        String now = Instant.now().toString();
        for (String hhId : hhIds) {
            jdbc.update("UPDATE vacancies SET notified=1, updated_at=? WHERE hh_id=?", now, hhId);
        }
    }

    /**
     * Get unnotified approved vacancies for Telegram report.
     */
    public List<Vacancy> findUnnotifiedApproved(int minScore, int limit) {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE ai_verdict='yes' AND ai_score >= ? AND notified = 0 " +
            "ORDER BY ai_score DESC, published_at DESC LIMIT ?",
            rowMapper, minScore, limit);
    }

    /**
     * Count pending (not yet AI-analyzed) vacancies.
     */
    public int countPending() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'", Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Find pending vacancies for AI analysis.
     */
    public List<Vacancy> findPending(int limit) {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE ai_verdict = 'pending' ORDER BY published_at DESC LIMIT ?",
            rowMapper, limit);
    }

    public void updateStatus(Long id, String status) {
        String now = Instant.now().toString();
        if ("applied".equals(status)) {
            jdbc.update("UPDATE vacancies SET status=?, applied_at=?, updated_at=? WHERE id=?",
                status, now, now, id);
        } else {
            jdbc.update("UPDATE vacancies SET status=?, updated_at=? WHERE id=?",
                status, now, id);
        }
    }

    public void updateStatusBulk(List<Long> ids, String status) {
        String now = Instant.now().toString();
        String sql = "applied".equals(status)
            ? "UPDATE vacancies SET status=?, applied_at=?, updated_at=? WHERE id=?"
            : "UPDATE vacancies SET status=?, updated_at=? WHERE id=?";

        jdbc.batchUpdate(sql, ids, 100, (ps, id) -> {
            if ("applied".equals(status)) {
                ps.setString(1, status);
                ps.setString(2, now);
                ps.setString(3, now);
                ps.setLong(4, id);
            } else {
                ps.setString(1, status);
                ps.setString(2, now);
                ps.setLong(3, id);
            }
        });
    }

    public void deleteById(Long id) {
        jdbc.update("DELETE FROM vacancies WHERE id = ?", id);
    }

    public boolean existsByHhId(String hhId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE hh_id = ?", Integer.class, hhId);
        return count != null && count > 0;
    }

    // Stats
    public Map<String, Integer> countByStatus() {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) as cnt FROM vacancies GROUP BY status", rs -> {
            result.put(rs.getString("status"), rs.getInt("cnt"));
        });
        // Add pending (unassessed) count
        Integer pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'", Integer.class);
        result.put("pending", pending != null ? pending : 0);
        // Add fraud count
        Integer fraud = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'fraud'", Integer.class);
        result.put("fraud", fraud != null ? fraud : 0);
        // Add truly new count: status='new' AND ai_verdict='pending' (not yet AI-analyzed)
        Integer newPending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE status = 'new' AND ai_verdict = 'pending'", Integer.class);
        result.put("newPending", newPending != null ? newPending : 0);
        return result;
    }

    public int countTotal() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies", Integer.class);
        return count != null ? count : 0;
    }

    public Double avgScoreNew() {
        return jdbc.queryForObject(
            "SELECT AVG(ai_score) FROM vacancies WHERE status='new'", Double.class);
    }

    public Double avgSalaryNew() {
        return jdbc.queryForObject(
            "SELECT AVG(CASE WHEN salary_to > 0 THEN salary_to ELSE salary_from END) " +
            "FROM vacancies WHERE (salary_to > 0 OR salary_from > 0) AND status='new'", Double.class);
    }

    public int countAppliedLast7Days() {
        String sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 3600).toString();
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE status='applied' AND applied_at > ?",
            Integer.class, sevenDaysAgo);
        return count != null ? count : 0;
    }

    /**
     * Find vacancies eligible for re-analysis:
     * ai_verdict != 'no' AND status != 'rejected'
     */
    public List<Vacancy> findRescanable() {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE ai_verdict NOT IN ('no', 'fraud') AND (status IS NULL OR status != 'rejected') " +
            "ORDER BY published_at DESC",
            rowMapper);
    }

    /**
     * Count vacancies eligible for re-analysis.
     */
    public int countRescanable() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE ai_verdict NOT IN ('no', 'fraud') AND (status IS NULL OR status != 'rejected')",
            Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Count unassessed vacancies (ai_score = 0 or no verdict).
     */
    public int countUnassessed() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE (ai_score = 0 OR ai_verdict IS NULL OR ai_verdict = '') AND ai_verdict != 'fraud'",
            Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Reset AI results for re-analysis (set to pending).
     * Only for vacancies where ai_verdict != 'no' AND status != 'rejected'.
     * Returns number of reset vacancies.
     */
    public int resetAiForRescan() {
        String now = Instant.now().toString();
        return jdbc.update(
            "UPDATE vacancies SET ai_verdict='pending', ai_score=0, ai_reason='', updated_at=? " +
            "WHERE ai_verdict NOT IN ('no', 'fraud') AND (status IS NULL OR status != 'rejected')",
            now);
    }

    public List<Map<String, Object>> topDistricts(int limit) {
        return jdbc.queryForList(
            "SELECT district, COUNT(*) as cnt FROM vacancies WHERE district != '' " +
            "GROUP BY district ORDER BY cnt DESC LIMIT ?", limit);
    }
}
