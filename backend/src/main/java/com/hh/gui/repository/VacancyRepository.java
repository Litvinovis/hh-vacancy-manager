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
import java.sql.Types;
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
            v.setPerson(rs.getString("person"));
            v.setSearchName(rs.getString("search_name"));
            v.setTitle(rs.getString("title"));
            v.setCompany(rs.getString("company"));
            v.setEmployerName(rs.getString("employer_name"));
            v.setSalaryFrom(rs.getInt("salary_from"));
            v.setSalaryTo(rs.getInt("salary_to"));
            v.setCurrency(rs.getString("currency"));
            v.setSalaryGross(rs.getInt("salary_gross") == 1);
            v.setAddress(rs.getString("address"));
            v.setDistrict(rs.getString("district"));
            v.setUrl(rs.getString("url"));
            v.setExperience(rs.getString("experience"));
            v.setEmployment(rs.getString("employment"));
            v.setKeySkills(rs.getString("key_skills"));
            v.setTrustedEmployer(rs.getInt("trusted_employer") == 1);
            v.setValidThrough(rs.getString("valid_through"));
            v.setScrapeStatus(rs.getString("scrape_status"));
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
            long userId = rs.getLong("user_id");
            v.setUserId(rs.wasNull() ? null : userId);
            long searchId = rs.getLong("search_id");
            v.setSearchId(rs.wasNull() ? null : searchId);
            v.setCriteriaHash(rs.getString("criteria_hash"));
            return v;
        };
    }

    public List<Vacancy> findAll(String status, String district, Integer minSalary,
                                  Integer minScore, String search, String tag,
                                  Boolean remote, String person, String searchName, Long userId,
                                  String sort, int offset, int limit) {
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
        if (person != null && !person.isEmpty()) {
            conditions.add("v.person = ?");
            params.add(person);
        }
        if (searchName != null && !searchName.isEmpty()) {
            conditions.add("v.search_name = ?");
            params.add(searchName);
        }
        if (userId != null) {
            conditions.add("v.user_id = ?");
            params.add(userId);
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
                         Integer minScore, String search, String tag, Boolean remote,
                         String person, String searchName, Long userId) {
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
        if (person != null && !person.isEmpty()) {
            conditions.add("v.person = ?");
            params.add(person);
        }
        if (searchName != null && !searchName.isEmpty()) {
            conditions.add("v.search_name = ?");
            params.add(searchName);
        }
        if (userId != null) {
            conditions.add("v.user_id = ?");
            params.add(userId);
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
            INSERT INTO vacancies (hh_id, person, search_name, user_id, search_id, criteria_hash,
                title, company, employer_name,
                salary_from, salary_to, currency, salary_gross, address, district, url,
                experience, employment, key_skills, trusted_employer, valid_through, scrape_status,
                ai_score, ai_verdict, ai_reason, description, status,
                rejection_reason, notes, applied_at, created_at, updated_at,
                source, source_query, is_remote, notified, published_at, found_by_scan)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, v.getHhId());
            ps.setString(i++, v.getPerson());
            ps.setString(i++, v.getSearchName());
            ps.setObject(i++, v.getUserId(), Types.BIGINT);
            ps.setObject(i++, v.getSearchId(), Types.BIGINT);
            ps.setString(i++, v.getCriteriaHash());
            ps.setString(i++, v.getTitle());
            ps.setString(i++, v.getCompany());
            ps.setString(i++, v.getEmployerName());
            ps.setInt(i++, v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
            ps.setInt(i++, v.getSalaryTo() != null ? v.getSalaryTo() : 0);
            ps.setString(i++, v.getCurrency());
            ps.setInt(i++, v.isSalaryGross() ? 1 : 0);
            ps.setString(i++, v.getAddress());
            ps.setString(i++, v.getDistrict());
            ps.setString(i++, v.getUrl());
            ps.setString(i++, v.getExperience());
            ps.setString(i++, v.getEmployment());
            ps.setString(i++, v.getKeySkills());
            ps.setInt(i++, v.isTrustedEmployer() ? 1 : 0);
            ps.setString(i++, v.getValidThrough());
            ps.setString(i++, v.getScrapeStatus() != null ? v.getScrapeStatus() : "pending");
            ps.setInt(i++, v.getAiScore() != null ? v.getAiScore() : 0);
            ps.setString(i++, v.getAiVerdict());
            ps.setString(i++, v.getAiReason());
            ps.setString(i++, v.getDescription());
            ps.setString(i++, v.getStatus());
            ps.setString(i++, v.getRejectionReason());
            ps.setString(i++, v.getNotes());
            ps.setString(i++, v.getAppliedAt());
            ps.setString(i++, v.getCreatedAt());
            ps.setString(i++, v.getUpdatedAt());
            ps.setString(i++, v.getSource());
            ps.setString(i++, v.getSourceQuery());
            ps.setInt(i++, v.isRemote() ? 1 : 0);
            ps.setInt(i++, v.isNotified() ? 1 : 0);
            ps.setString(i++, v.getPublishedAt());
            ps.setInt(i, v.getFoundByScan());
            return ps;
        }, keyHolder);

        v.setId(keyHolder.getKey().longValue());
        return v;
    }

    public void update(Vacancy v) {
        String now = Instant.now().toString();
        v.setUpdatedAt(now);

        String sql = """
            UPDATE vacancies SET hh_id=?, person=?, search_name=?, user_id=?, search_id=?, criteria_hash=?,
                title=?, company=?, employer_name=?,
                salary_from=?, salary_to=?, currency=?, salary_gross=?, address=?, district=?, url=?,
                experience=?, employment=?, key_skills=?, trusted_employer=?, valid_through=?, scrape_status=?,
                ai_score=?, ai_verdict=?, ai_reason=?, description=?, status=?, rejection_reason=?, notes=?,
                applied_at=?, updated_at=?, source=?, source_query=?, is_remote=?,
                notified=?, published_at=?, found_by_scan=?
            WHERE id=?
            """;

        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            int i = 1;
            ps.setString(i++, v.getHhId());
            ps.setString(i++, v.getPerson());
            ps.setString(i++, v.getSearchName());
            ps.setObject(i++, v.getUserId(), Types.BIGINT);
            ps.setObject(i++, v.getSearchId(), Types.BIGINT);
            ps.setString(i++, v.getCriteriaHash());
            ps.setString(i++, v.getTitle());
            ps.setString(i++, v.getCompany());
            ps.setString(i++, v.getEmployerName());
            ps.setInt(i++, v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
            ps.setInt(i++, v.getSalaryTo() != null ? v.getSalaryTo() : 0);
            ps.setString(i++, v.getCurrency());
            ps.setInt(i++, v.isSalaryGross() ? 1 : 0);
            ps.setString(i++, v.getAddress());
            ps.setString(i++, v.getDistrict());
            ps.setString(i++, v.getUrl());
            ps.setString(i++, v.getExperience());
            ps.setString(i++, v.getEmployment());
            ps.setString(i++, v.getKeySkills());
            ps.setInt(i++, v.isTrustedEmployer() ? 1 : 0);
            ps.setString(i++, v.getValidThrough());
            ps.setString(i++, v.getScrapeStatus());
            ps.setInt(i++, v.getAiScore() != null ? v.getAiScore() : 0);
            ps.setString(i++, v.getAiVerdict());
            ps.setString(i++, v.getAiReason());
            ps.setString(i++, v.getDescription());
            ps.setString(i++, v.getStatus());
            ps.setString(i++, v.getRejectionReason());
            ps.setString(i++, v.getNotes());
            ps.setString(i++, v.getAppliedAt());
            ps.setString(i++, v.getUpdatedAt());
            ps.setString(i++, v.getSource());
            ps.setString(i++, v.getSourceQuery());
            ps.setInt(i++, v.isRemote() ? 1 : 0);
            ps.setInt(i++, v.isNotified() ? 1 : 0);
            ps.setString(i++, v.getPublishedAt());
            ps.setInt(i++, v.getFoundByScan());
            ps.setLong(i, v.getId());
            return ps;
        });
    }

    /**
     * Rows discovered via RSS but not yet (successfully) scraped for full content.
     * 'failed' rows are retried on the next pipeline run; 'not_found' is terminal
     * (archived/removed vacancy) and excluded here.
     */
    public List<Vacancy> findScrapePending(String person, String searchName, int limit) {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE person=? AND search_name=? " +
            "AND scrape_status IN ('pending', 'failed') ORDER BY created_at ASC LIMIT ?",
            rowMapper, person, searchName, limit);
    }

    /**
     * Persist the result of scraping a vacancy's full page (or a failed attempt) for an existing row.
     */
    public void updateScraped(Vacancy v) {
        String now = Instant.now().toString();
        jdbc.update("""
            UPDATE vacancies SET title=?, company=?, employer_name=?, description=?,
                salary_from=?, salary_to=?, currency=?, salary_gross=?,
                address=?, district=?, experience=?, employment=?, key_skills=?,
                trusted_employer=?, valid_through=?, scrape_status=?, is_remote=?, updated_at=?
            WHERE id=?
            """,
            v.getTitle(), v.getCompany(), v.getEmployerName(), v.getDescription(),
            v.getSalaryFrom() != null ? v.getSalaryFrom() : 0,
            v.getSalaryTo() != null ? v.getSalaryTo() : 0,
            v.getCurrency(), v.isSalaryGross() ? 1 : 0,
            v.getAddress(), v.getDistrict(), v.getExperience(), v.getEmployment(), v.getKeySkills(),
            v.isTrustedEmployer() ? 1 : 0, v.getValidThrough(), v.getScrapeStatus(),
            v.isRemote() ? 1 : 0, now, v.getId());
    }

    /**
     * Update AI analysis result for a specific (hh_id, person, search) row.
     * Scoped by person+search since the same hh_id can legitimately have one
     * row per (person, search) that discovered it, each with its own verdict.
     */
    public void updateAiResult(String hhId, String person, String searchName, int score, String verdict, String reason) {
        String now = Instant.now().toString();
        jdbc.update(
            "UPDATE vacancies SET ai_score=?, ai_verdict=?, ai_reason=?, updated_at=? " +
            "WHERE hh_id=? AND person=? AND search_name=?",
            score, verdict, reason, now, hhId, person, searchName);
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
     * Mark vacancies as notified (sent to Telegram). By primary key, not hh_id —
     * the same hh_id can appear as multiple rows (one per person/search) and only
     * the specific rows just reported on should be marked.
     */
    public void markNotified(List<Long> ids) {
        String now = Instant.now().toString();
        for (Long id : ids) {
            jdbc.update("UPDATE vacancies SET notified=1, updated_at=? WHERE id=?", now, id);
        }
    }

    /**
     * Get unnotified approved vacancies for a specific (person, search) Telegram report.
     */
    public List<Vacancy> findUnnotifiedApproved(String person, String searchName, int minScore, int limit) {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE person=? AND search_name=? " +
            "AND ai_verdict='yes' AND ai_score >= ? AND notified = 0 " +
            "ORDER BY ai_score DESC, published_at DESC LIMIT ?",
            rowMapper, person, searchName, minScore, limit);
    }

    /**
     * Count pending (not yet AI-analyzed) vacancies, scoped to userId unless null (admin/global).
     */
    public int countPending(Long userId) {
        String sql = "SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'" + (userId != null ? " AND user_id = ?" : "");
        Integer count = userId != null
            ? jdbc.queryForObject(sql, Integer.class, userId)
            : jdbc.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Find pending vacancies for AI analysis, scoped to a (person, search) —
     * different searches carry different scoring criteria and must not be batched together.
     */
    public List<Vacancy> findPending(String person, String searchName, int limit) {
        return jdbc.query(
            "SELECT * FROM vacancies WHERE ai_verdict = 'pending' AND scrape_status = 'ok' " +
            "AND person=? AND search_name=? ORDER BY published_at DESC LIMIT ?",
            rowMapper, person, searchName, limit);
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

    public boolean existsByHhIdPersonSearch(String hhId, String person, String searchName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies WHERE hh_id = ? AND person = ? AND search_name = ?",
            Integer.class, hhId, person, searchName);
        return count != null && count > 0;
    }

    /**
     * Vacancies previously scraped for this exact hh_id (any person/search) whose
     * scrape succeeded — lets the pipeline reuse already-fetched content instead of
     * re-scraping when a second (person, search) also matches the same real posting.
     */
    public Optional<Vacancy> findFirstScrapedByHhId(String hhId) {
        List<Vacancy> results = jdbc.query(
            "SELECT * FROM vacancies WHERE hh_id = ? AND scrape_status = 'ok' LIMIT 1",
            rowMapper, hhId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Mirrors findFirstScrapedByHhId one layer up: an already-AI-analyzed vacancy
     * for the same real hh_id whose scoring inputs (criteria_hash) exactly match —
     * lets the pipeline copy a verdict instead of spending another AI call when two
     * different users' searches are genuinely scoring-equivalent for this posting.
     */
    public Optional<Vacancy> findAnalyzedByHhIdAndCriteriaHash(String hhId, String criteriaHash) {
        if (criteriaHash == null || criteriaHash.isEmpty()) return Optional.empty();
        List<Vacancy> results = jdbc.query(
            "SELECT * FROM vacancies WHERE hh_id = ? AND criteria_hash = ? AND ai_verdict != 'pending' LIMIT 1",
            rowMapper, hhId, criteriaHash);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void updateCriteriaHash(Long id, String criteriaHash) {
        jdbc.update("UPDATE vacancies SET criteria_hash=? WHERE id=?", criteriaHash, id);
    }

    // Stats — every method below is scoped to userId unless null (admin sees everything)
    public Map<String, Integer> countByStatus(Long userId) {
        String scope = userId != null ? " AND user_id = ?" : "";
        Map<String, Integer> result = new LinkedHashMap<>();
        if (userId != null) {
            jdbc.query("SELECT status, COUNT(*) as cnt FROM vacancies WHERE user_id = ? GROUP BY status",
                (rs) -> { result.put(rs.getString("status"), rs.getInt("cnt")); }, userId);
        } else {
            jdbc.query("SELECT status, COUNT(*) as cnt FROM vacancies GROUP BY status",
                (rs) -> { result.put(rs.getString("status"), rs.getInt("cnt")); });
        }

        Integer pending = queryCount("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'" + scope, userId);
        result.put("pending", pending);
        Integer fraud = queryCount("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'fraud'" + scope, userId);
        result.put("fraud", fraud);
        Integer newPending = queryCount("SELECT COUNT(*) FROM vacancies WHERE status = 'new' AND ai_verdict = 'pending'" + scope, userId);
        result.put("newPending", newPending);
        return result;
    }

    private int queryCount(String sql, Long userId) {
        Integer count = userId != null
            ? jdbc.queryForObject(sql, Integer.class, userId)
            : jdbc.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    public int countTotal(Long userId) {
        String sql = "SELECT COUNT(*) FROM vacancies" + (userId != null ? " WHERE user_id = ?" : "");
        return queryCount(sql, userId);
    }

    public Double avgScoreNew(Long userId) {
        String sql = "SELECT AVG(ai_score) FROM vacancies WHERE status='new'" + (userId != null ? " AND user_id = ?" : "");
        return userId != null
            ? jdbc.queryForObject(sql, Double.class, userId)
            : jdbc.queryForObject(sql, Double.class);
    }

    public Double avgSalaryNew(Long userId) {
        String sql = "SELECT AVG(CASE WHEN salary_to > 0 THEN salary_to ELSE salary_from END) " +
            "FROM vacancies WHERE (salary_to > 0 OR salary_from > 0) AND status='new'" + (userId != null ? " AND user_id = ?" : "");
        return userId != null
            ? jdbc.queryForObject(sql, Double.class, userId)
            : jdbc.queryForObject(sql, Double.class);
    }

    public int countAppliedLast7Days(Long userId) {
        String sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 3600).toString();
        String sql = "SELECT COUNT(*) FROM vacancies WHERE status='applied' AND applied_at > ?" + (userId != null ? " AND user_id = ?" : "");
        Integer count = userId != null
            ? jdbc.queryForObject(sql, Integer.class, sevenDaysAgo, userId)
            : jdbc.queryForObject(sql, Integer.class, sevenDaysAgo);
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
    public int countRescanable(Long userId) {
        String sql = "SELECT COUNT(*) FROM vacancies WHERE ai_verdict NOT IN ('no', 'fraud') AND (status IS NULL OR status != 'rejected')"
            + (userId != null ? " AND user_id = ?" : "");
        return queryCount(sql, userId);
    }

    /**
     * Count unassessed vacancies (ai_score = 0 or no verdict).
     */
    public int countUnassessed(Long userId) {
        String sql = "SELECT COUNT(*) FROM vacancies WHERE (ai_score = 0 OR ai_verdict IS NULL OR ai_verdict = '') AND ai_verdict != 'fraud'"
            + (userId != null ? " AND user_id = ?" : "");
        return queryCount(sql, userId);
    }

    /**
     * Reset AI results for re-analysis (set to pending), scoped to one (person, search)
     * job — different jobs score against different criteria, so a rescan for one
     * shouldn't touch another's already-analyzed rows.
     * Only for vacancies where ai_verdict != 'no'/'fraud' AND status != 'rejected'.
     * Returns number of reset vacancies.
     */
    public int resetAiForRescan(String person, String searchName) {
        String now = Instant.now().toString();
        return jdbc.update(
            "UPDATE vacancies SET ai_verdict='pending', ai_score=0, ai_reason='', updated_at=? " +
            "WHERE person=? AND search_name=? AND ai_verdict NOT IN ('no', 'fraud') AND (status IS NULL OR status != 'rejected')",
            now, person, searchName);
    }

    public List<Map<String, Object>> topDistricts(int limit, Long userId) {
        String sql = "SELECT district, COUNT(*) as cnt FROM vacancies WHERE district != ''"
            + (userId != null ? " AND user_id = ?" : "") + " GROUP BY district ORDER BY cnt DESC LIMIT ?";
        return userId != null
            ? jdbc.queryForList(sql, userId, limit)
            : jdbc.queryForList(sql, limit);
    }

    public List<Map<String, Object>> listPeople(Long userId) {
        String sql = "SELECT person, COUNT(*) as cnt FROM vacancies WHERE person != ''"
            + (userId != null ? " AND user_id = ?" : "") + " GROUP BY person ORDER BY person";
        return userId != null ? jdbc.queryForList(sql, userId) : jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> listSearches(String person, Long userId) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        conditions.add("search_name != ''");
        if (person != null && !person.isEmpty()) {
            conditions.add("person = ?");
            params.add(person);
        }
        if (userId != null) {
            conditions.add("user_id = ?");
            params.add(userId);
        }
        String sql = "SELECT search_name, COUNT(*) as cnt FROM vacancies WHERE "
            + String.join(" AND ", conditions) + " GROUP BY search_name ORDER BY search_name";
        return jdbc.queryForList(sql, params.toArray());
    }
}
