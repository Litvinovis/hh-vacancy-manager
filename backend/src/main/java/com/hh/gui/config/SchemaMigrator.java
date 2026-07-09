package com.hh.gui.config;

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

/**
 * Adds columns introduced after a DB was first created — schema.sql's
 * "CREATE TABLE IF NOT EXISTS" only applies to brand-new databases, so an
 * existing installation (e.g. the running production DB) never picks up new
 * columns on existing tables without this. New tables don't need this — schema.sql's
 * CREATE TABLE IF NOT EXISTS already handles those on every boot, old or new.
 *
 * Runs before FirstBootSeeder (see @Order) so the seeded admin/searches always
 * see the final column set, though seeding itself doesn't depend on it.
 */
@Component
@Order(0)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("searches", "is_global", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("searches", "source_url", "TEXT DEFAULT ''");
        addColumnIfMissing("searches", "run_interval_hours", "INTEGER DEFAULT NULL");
        addColumnIfMissing("searches", "last_run_at", "TEXT DEFAULT NULL");
        addColumnIfMissing("vacancies", "dedup_key", "TEXT DEFAULT ''");
        addColumnIfMissing("vacancies", "scrape_attempts", "INTEGER DEFAULT 0");
        addColumnIfMissing("vacancies", "ai_attempts", "INTEGER DEFAULT 0");

        // Not in schema.sql: an index on a just-added column would fail schema.sql's own
        // unconditional run on the next boot after a fresh install (see schema.sql's
        // comment) — these are safe here since the columns above are guaranteed to exist
        // by this point, and CREATE INDEX IF NOT EXISTS is a no-op on later boots anyway.
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_vac_dedup_key ON vacancies(dedup_key)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_searches_is_global ON searches(is_global)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_uvs_user_id ON user_vacancy_status(user_id)");
        runIgnoringErrors("CREATE INDEX IF NOT EXISTS idx_uvs_vacancy_id ON user_vacancy_status(vacancy_id)");
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
