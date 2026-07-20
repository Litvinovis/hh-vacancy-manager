package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.util.DedupKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Re-processes the v1 archive (vacancies_legacy) through the current pipeline.
 *
 * Why it exists: v1 analyzed vacancies "blindly" — RSS carried no real posting
 * text (the archived rows average ~130 chars of description), so none of the
 * old verdicts can be trusted. A liveness probe over a random sample showed
 * ~95% of the archived postings are still live on hh.ru (long-running mass
 * postings), so they're worth a real scrape + analysis.
 *
 * Each batch: picks unmigrated legacy rows, maps them onto today's search jobs
 * (is_remote=1 → "Удалёнка по России", 0 → "Рядом с домом" — the v1 profile was
 * exactly these two searches, see FirstBootSeeder), applies the same pre-scrape
 * filters new discoveries get (exclude words, salary floor, AI card prescreen
 * with clone collapsing), and inserts survivors as scrape-pending stubs with
 * created_at=now — deliberately NOT the legacy date, since the scrape queue is
 * created_at-ordered and old dates would put the whole archive AHEAD of fresh
 * discoveries. From there the normal pipeline takes over: dead postings die at
 * the scrape step (404/архив), live ones get scraped and honestly analyzed.
 *
 * Every considered legacy row is stamped migrated_at (imported, prescreen-
 * rejected and filter-excluded alike) so batches never revisit rows. Rows
 * without an hh_id are unrecoverable and simply never selected.
 */
@Service
public class LegacyImportService {

    private static final Logger log = LoggerFactory.getLogger(LegacyImportService.class);

    // Raised from the initial cautious 200 after a week of live data: the sidecar
    // sustains ~1900 scrapes/day with fresh discoveries at ~500/day, so a 500-row
    // nightly batch (~5-6h of morning scraper time) still leaves headroom — it does
    // push freshness re-checks (which yield to the scrape queue) later into the day,
    // a deliberate trade to drain the archive in ~2 weeks instead of a month.
    public static final int DAILY_BATCH = 500;
    // The v1 archive belongs to these two seeded searches (see class javadoc).
    static final long REMOTE_SEARCH_ID = 1;
    static final long LOCAL_SEARCH_ID = 2;

    private final JdbcTemplate jdbc;
    private final VacancyRepository vacancyRepo;
    private final VacancyAiAnalyzer aiAnalyzer;
    private final SearchProfileFactory profileFactory;

    public LegacyImportService(JdbcTemplate jdbc, VacancyRepository vacancyRepo,
                               VacancyAiAnalyzer aiAnalyzer, SearchProfileFactory profileFactory) {
        this.jdbc = jdbc;
        this.vacancyRepo = vacancyRepo;
        this.aiAnalyzer = aiAnalyzer;
        this.profileFactory = profileFactory;
    }

    record LegacyRow(long id, String hhId, String title, String company,
                     Integer salaryFrom, Integer salaryTo, String currency,
                     boolean remote, String url, String verdict) {}

    public static class ImportResult {
        public int considered;
        public int alreadyPresent;
        public int excluded;
        public int prescreenRejected;
        public int imported;
        public String error;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (error != null) m.put("error", error);
            m.put("considered", considered);
            m.put("alreadyPresent", alreadyPresent);
            m.put("excluded", excluded);
            m.put("prescreenRejected", prescreenRejected);
            m.put("imported", imported);
            return m;
        }
    }

    /**
     * One import batch. onlyYes limits selection to rows v1 scored "yes" — used
     * for the pilot pass over the most promising slice of the archive.
     */
    public synchronized ImportResult importBatch(int limit, boolean onlyYes) {
        ImportResult result = new ImportResult();
        Optional<SearchJob> remoteJob = profileFactory.buildForSearchId(REMOTE_SEARCH_ID);
        Optional<SearchJob> localJob = profileFactory.buildForSearchId(LOCAL_SEARCH_ID);
        if (remoteJob.isEmpty() || localJob.isEmpty()) {
            result.error = "поиски id=1/2 не найдены — маппинг legacy-архива невозможен";
            log.warn("Импорт legacy: {}", result.error);
            return result;
        }

        List<LegacyRow> rows = fetchUnmigrated(limit, onlyYes);
        if (rows.isEmpty()) return result;
        result.considered = rows.size();

        List<LegacyRow> candidates = new ArrayList<>();
        for (LegacyRow row : rows) {
            if (hhIdExistsAnywhere(row.hhId())) {
                // v2 already re-discovered this posting on its own — nothing to migrate.
                result.alreadyPresent++;
                continue;
            }
            SearchJob job = row.remote() ? remoteJob.get() : localJob.get();
            if (isExcluded(row, job)) {
                result.excluded++;
                continue;
            }
            candidates.add(row);
        }

        // Prescreen per job — batches must not mix jobs, their criteria differ.
        for (boolean remote : new boolean[]{true, false}) {
            SearchJob job = remote ? remoteJob.get() : localJob.get();
            List<LegacyRow> group = candidates.stream().filter(r -> r.remote() == remote).toList();
            if (group.isEmpty()) continue;

            Map<String, VacancyAiAnalyzer.AiResult> prescreen = aiAnalyzer.prescreenHits(
                group.stream().map(LegacyImportService::toHit).toList(), job).stream()
                .collect(Collectors.toMap(VacancyAiAnalyzer.AiResult::hhId, r -> r, (a, b) -> a));

            for (LegacyRow row : group) {
                VacancyAiAnalyzer.AiResult verdict = prescreen.get(row.hhId());
                boolean passed = verdict == null || !"no".equals(verdict.verdict());
                Vacancy v = toStub(row, job);
                if (passed) {
                    v.setAiVerdict("pending");
                    v.setScrapeStatus("pending");
                } else {
                    v.setAiVerdict("no");
                    v.setAiReason("Прескрининг (импорт из архива v1): " + verdict.reason());
                    v.setScrapeStatus("skipped");
                }
                try {
                    vacancyRepo.save(v);
                    if (passed) result.imported++;
                    else result.prescreenRejected++;
                } catch (Exception e) {
                    log.warn("Импорт legacy: не удалось сохранить {}: {}", row.hhId(), e.getMessage());
                }
            }
        }

        stampMigrated(rows.stream().map(LegacyRow::id).toList());
        log.info("Импорт legacy-архива: рассмотрено {}, уже в базе {}, отфильтровано {}, отсеяно прескрином {}, импортировано {}",
            result.considered, result.alreadyPresent, result.excluded, result.prescreenRejected, result.imported);
        return result;
    }

    /** Unmigrated legacy rows remain? Lets the scheduler stop firing once the archive is drained. */
    public boolean hasUnmigrated() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM vacancies_legacy WHERE migrated_at IS NULL AND hh_id IS NOT NULL AND hh_id != ''",
            Integer.class);
        return count != null && count > 0;
    }

    private static boolean isExcluded(LegacyRow row, SearchJob job) {
        String title = row.title() != null ? row.title().toLowerCase() : "";
        if (job.excludeWords != null && job.excludeWords.stream().map(String::toLowerCase).anyMatch(title::contains)) {
            return true;
        }
        // Same deterministic reject analyzeBatchWithDedup applies: an explicit ceiling
        // below the job's floor can't become a "yes".
        if (job.salaryMin > 0 && row.salaryTo() != null && row.salaryTo() > 0 && row.salaryTo() < job.salaryMin) {
            String cur = row.currency();
            return cur == null || cur.isBlank() || "RUR".equalsIgnoreCase(cur) || "RUB".equalsIgnoreCase(cur);
        }
        return false;
    }

    private static ScraperClient.SearchHit toHit(LegacyRow row) {
        String salary = null;
        if (row.salaryFrom() != null && row.salaryFrom() > 0) salary = "от " + row.salaryFrom();
        if (row.salaryTo() != null && row.salaryTo() > 0) salary = (salary != null ? salary + " " : "") + "до " + row.salaryTo();
        return new ScraperClient.SearchHit(row.hhId(), row.title(), row.company(), salary, null, null, row.url());
    }

    private static Vacancy toStub(LegacyRow row, SearchJob job) {
        Vacancy v = new Vacancy();
        v.setHhId(row.hhId());
        v.setTitle(row.title());
        v.setCompany(row.company());
        v.setUrl(row.url() != null && !row.url().isBlank() ? row.url() : "https://hh.ru/vacancy/" + row.hhId());
        v.setStatus("new");
        // now, not the legacy date — see class javadoc (scrape queue is created_at-ordered).
        v.setCreatedAt(Instant.now().toString());
        v.setSource("hh-legacy");
        v.setSourceQuery(job.searchName);
        v.setPerson(job.personName);
        v.setSearchName(job.searchName);
        v.setUserId(job.isGlobal ? null : job.userId);
        v.setSearchId(job.searchId);
        v.setRemote(job.isRemote());
        v.setAiScore(0);
        v.setDedupKey(DedupKeys.compute(row.title(), row.company()));
        return v;
    }

    // ── Thin data-access seams (protected for tests) ──

    protected List<LegacyRow> fetchUnmigrated(int limit, boolean onlyYes) {
        String sql = "SELECT id, hh_id, title, company, salary_from, salary_to, currency, is_remote, url, ai_verdict " +
            "FROM vacancies_legacy WHERE migrated_at IS NULL AND hh_id IS NOT NULL AND hh_id != '' " +
            (onlyYes ? "AND ai_verdict = 'yes' " : "") +
            "ORDER BY id LIMIT ?";
        return jdbc.query(sql, (rs, i) -> new LegacyRow(
            rs.getLong("id"), rs.getString("hh_id"), rs.getString("title"), rs.getString("company"),
            (Integer) rs.getObject("salary_from"), (Integer) rs.getObject("salary_to"), rs.getString("currency"),
            rs.getInt("is_remote") == 1, rs.getString("url"), rs.getString("ai_verdict")), limit);
    }

    protected boolean hhIdExistsAnywhere(String hhId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies WHERE hh_id = ?", Integer.class, hhId);
        return count != null && count > 0;
    }

    protected void stampMigrated(List<Long> ids) {
        if (ids.isEmpty()) return;
        String now = Instant.now().toString();
        jdbc.batchUpdate("UPDATE vacancies_legacy SET migrated_at = ? WHERE id = ?", ids, ids.size(),
            (ps, id) -> {
                ps.setString(1, now);
                ps.setLong(2, id);
            });
    }
}
