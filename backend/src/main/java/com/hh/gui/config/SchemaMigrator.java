package com.hh.gui.config;

import com.hh.gui.util.DedupKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds columns introduced after a DB was first created — schema.sql's
 * "CREATE TABLE IF NOT EXISTS" only applies to brand-new databases, so an
 * existing installation (e.g. the running production DB) never picks up new
 * columns on existing tables without this. New tables don't need this — schema.sql's
 * CREATE TABLE IF NOT EXISTS already handles those on every boot, old or new.
 *
 * Runs before FirstBootSeeder (see @Order) so the seeded admin/searches always
 * see the final column set, though seeding itself doesn't depend on it.
 *
 * ApplicationRunners (this class included) run in SpringApplication.callRunners(),
 * which happens AFTER the context has fully refreshed — and @Scheduled/trigger tasks
 * (see PipelineScheduler) activate on ContextRefreshedEvent, which fires as part of
 * that refresh, strictly BEFORE callRunners(). So a deploy that adds a column has a
 * real window where the first scheduler tick queries searches/vacancies before this
 * has added it (observed 2026-08-11: "no such column: chat_id" / "public_format" —
 * self-healing, one error log per boot, no data loss, but worth closing). isReady()
 * lets PipelineScheduler's DB-touching tasks wait it out instead of racing it.
 */
@Component
@Order(0)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;
    private volatile boolean ready = false;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("searches", "is_global", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("searches", "source_url", "TEXT DEFAULT ''");
        addColumnIfMissing("searches", "run_interval_hours", "INTEGER DEFAULT NULL");
        addColumnIfMissing("searches", "last_run_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("searches", "chat_id", "TEXT DEFAULT NULL");
        addColumnIfMissing("searches", "public_format", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("searches", "search_kind", "TEXT NOT NULL DEFAULT 'PERSONAL'");
        addColumnIfMissing("searches", "delayed_chat_id", "TEXT DEFAULT NULL");
        addColumnIfMissing("searches", "delayed_publish_minutes", "INTEGER DEFAULT NULL");
        addColumnIfMissing("searches", "subscriber_feed", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("searches", "publish_pace_minutes", "INTEGER DEFAULT NULL");
        addColumnIfMissing("searches", "run_priority", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("searches", "telegram_channels", "TEXT DEFAULT '[]'");
        addColumnIfMissing("vacancies", "dedup_key", "TEXT DEFAULT ''");
        addColumnIfMissing("vacancies", "scrape_attempts", "INTEGER DEFAULT 0");
        addColumnIfMissing("vacancies", "ai_attempts", "INTEGER DEFAULT 0");
        addColumnIfMissing("vacancies", "last_checked_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("vacancies", "closed_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("vacancies", "delayed_publish_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("vacancies", "delayed_notified", "INTEGER DEFAULT 0");
        addColumnIfMissing("vacancies", "queued_publish_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("vacancies", "novelty_color", "TEXT DEFAULT ''");
        addColumnIfMissing("vacancies", "novelty_note", "TEXT DEFAULT ''");
        addColumnIfMissing("subscriptions", "cancel_requested", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("subscriptions", "renewal_reminder_sent_at", "TEXT DEFAULT NULL");

        // Not in schema.sql: an index on a just-added column would fail schema.sql's own
        // unconditional run on the next boot after a fresh install (see schema.sql's
        // comment) — these are safe here since the columns above are guaranteed to exist
        // by this point, and CREATE INDEX IF NOT EXISTS is a no-op on later boots anyway.
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_vac_dedup_key ON vacancies(dedup_key)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_searches_is_global ON searches(is_global)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_uvs_user_id ON user_vacancy_status(user_id)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_uvs_vacancy_id ON user_vacancy_status(vacancy_id)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_vac_delayed_publish_at ON vacancies(delayed_publish_at)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_vac_queued_publish_at ON vacancies(queued_publish_at)");
        // Also declared in schema.sql (all its columns predate this migrator) — repeated
        // here so an existing database picks it up on the very boot that ships it,
        // without waiting for schema.sql's own run order. See schema.sql for the measured
        // 205 ms → 3 ms this buys on findUnnotifiedApproved.
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_vac_approved_unnotified "
            + "ON vacancies(person, search_name, ai_verdict, notified, ai_score DESC)");

        backfillDedupKeys();
        backfillSearchKind();
        // The database shipped with no sqlite_stat1 at all, so every plan was chosen from
        // SQLite's built-in guesses. With stats the dedup_key subquery in
        // findUnnotifiedApproved switches from idx_vac_notified to idx_vac_dedup_key —
        // the last 3 ms → 1 ms. Measured at 104 ms over 48k rows, so it is cheap enough
        // to redo on each boot and keeps up as the table grows.
        runIgnoringErrors("ANALYZE");
        ready = true;
    }

    /**
     * dedup_key used to be set only on the URL-discovery path, so RSS-discovered rows
     * (the vast majority) never got one and the cross-city clone reuse in
     * VacancyPipelineService couldn't fire for them. New rows get their key when
     * scraped; this fills it in for already-scraped history. Also recomputes keys
     * ending in '|' — an earlier version built employer-less keys, which could
     * cross-match unrelated companies sharing a generic title (DedupKeys.compute now
     * returns "" for those). No-op on every boot after the first.
     */
    /**
     * Existing channel searches predate SearchKind — they were told apart only by
     * public_format, which describes how posts are FORMATTED, not what question the
     * search asks. One-time backfill so the distinction starts out matching reality;
     * from here on the two are set independently.
     */
    private void backfillSearchKind() {
        try {
            int updated = jdbc.update(
                "UPDATE searches SET search_kind = 'EDITORIAL' WHERE public_format = 1 AND search_kind = 'PERSONAL'");
            if (updated > 0) {
                log.info("Миграция данных: {} поисков с публичным форматом помечены как EDITORIAL", updated);
            }
        } catch (Exception e) {
            log.error("Миграция данных: бэкфилл search_kind не удался: {}", e.getMessage());
        }
    }

    private void backfillDedupKeys() {
        try {
            var rows = jdbc.queryForList(
                "SELECT id, title, COALESCE(NULLIF(employer_name, ''), company) AS employer FROM vacancies " +
                "WHERE scrape_status = 'ok' AND (dedup_key IS NULL OR dedup_key = '' OR dedup_key LIKE '%|')");
            if (rows.isEmpty()) return;
            List<Object[]> updates = new ArrayList<>();
            for (var row : rows) {
                String key = DedupKeys.compute((String) row.get("title"), (String) row.get("employer"));
                if (!key.isEmpty()) {
                    updates.add(new Object[]{key, row.get("id")});
                }
            }
            jdbc.batchUpdate("UPDATE vacancies SET dedup_key = ? WHERE id = ?", updates);
            log.info("Миграция данных: dedup_key заполнен для {} из {} строк без ключа", updates.size(), rows.size());
        } catch (Exception e) {
            log.error("Миграция данных: бэкфилл dedup_key не удался: {}", e.getMessage());
        }
    }

    private void runIgnoringErrors(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.error("Миграция схемы: не удалось выполнить '{}': {}", sql, e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            if (columnExists(table, column)) return;
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Миграция схемы: добавлена колонка {}.{}", table, column);
        } catch (Exception e) {
            log.error("Миграция схемы: не удалось добавить {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection con = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = con.getMetaData();
            // Column names are stored uppercase by some drivers (e.g. H2) and as-is by others
            // (SQLite) — check both instead of relying on getColumns()'s own case sensitivity.
            try (ResultSet rs = meta.getColumns(null, null, table, column)) {
                if (rs.next()) return true;
            }
            try (ResultSet rs = meta.getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
                return rs.next();
            }
        }
    }
}
