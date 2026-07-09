-- HH Vacancy Manager — Schema
-- Compatible with SQLite

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    city TEXT DEFAULT '',
    experience_summary TEXT DEFAULT '',
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS searches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    queries TEXT NOT NULL DEFAULT '[]',
    area INTEGER NOT NULL DEFAULT 113,
    schedule TEXT DEFAULT '',
    salary_min INTEGER DEFAULT 0,
    priority_districts TEXT DEFAULT '[]',
    skills TEXT DEFAULT '[]',
    not_suitable TEXT DEFAULT '[]',
    exclude_words TEXT DEFAULT '[]',
    ai_notes TEXT DEFAULT '',
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    -- is_global: search created by an admin, visible to every user (not just user_id,
    -- who is just the admin that manages it) and exempt from the per-user search cap.
    is_global INTEGER NOT NULL DEFAULT 0,
    -- source_url/run_interval_hours: persisted hh.ru search-results URL + how often
    -- (in hours) to run it automatically; NULL interval = manual-trigger only, as before.
    source_url TEXT DEFAULT '',
    run_interval_hours INTEGER DEFAULT NULL,
    last_run_at TEXT DEFAULT NULL,
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS vacancies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hh_id TEXT,
    person TEXT DEFAULT '',
    search_name TEXT DEFAULT '',
    user_id INTEGER DEFAULT NULL,
    search_id INTEGER DEFAULT NULL,
    criteria_hash TEXT DEFAULT '',
    title TEXT NOT NULL DEFAULT '',
    company TEXT DEFAULT '',
    employer_name TEXT DEFAULT '',
    salary_from INTEGER DEFAULT 0,
    salary_to INTEGER DEFAULT 0,
    currency TEXT DEFAULT 'RUR',
    salary_gross INTEGER DEFAULT 0,
    address TEXT DEFAULT '',
    district TEXT DEFAULT '',
    url TEXT DEFAULT '',
    experience TEXT DEFAULT '',
    employment TEXT DEFAULT '',
    key_skills TEXT DEFAULT '',
    trusted_employer INTEGER DEFAULT 0,
    valid_through TEXT DEFAULT '',
    scrape_status TEXT NOT NULL DEFAULT 'pending',
    ai_score INTEGER DEFAULT 0,
    ai_verdict TEXT DEFAULT 'pending',
    ai_reason TEXT DEFAULT '',
    description TEXT DEFAULT '',
    status TEXT NOT NULL DEFAULT 'new',
    rejection_reason TEXT DEFAULT '',
    notes TEXT DEFAULT '',
    applied_at TEXT DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT '',
    source TEXT DEFAULT 'hh',
    source_query TEXT DEFAULT '',
    is_remote INTEGER DEFAULT 0,
    notified INTEGER DEFAULT 0,
    published_at TEXT DEFAULT '',
    found_by_scan INTEGER DEFAULT 0,
    -- dedup_key: normalized title+employer (city-independent) — lets the pipeline
    -- recognize "same real posting, different city" (e.g. the same remote support
    -- role listed separately for Moscow and Ufa) even though hh_id differs.
    dedup_key TEXT DEFAULT '',
    UNIQUE(hh_id, person, search_name)
);

-- Per-user overlay for shared (is_global) search results: each user tracks their own
-- status/notes/response for a vacancy that everyone sees, without duplicating the
-- vacancy row itself. Vacancies from personal searches never get a row here — their
-- status lives directly on vacancies, unchanged.
CREATE TABLE IF NOT EXISTS user_vacancy_status (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vacancy_id INTEGER NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'new',
    rejection_reason TEXT DEFAULT '',
    notes TEXT DEFAULT '',
    applied_at TEXT DEFAULT '',
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, vacancy_id)
);

CREATE TABLE IF NOT EXISTS tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vacancy_id INTEGER NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vacancy_id INTEGER NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    details TEXT DEFAULT '',
    created_at TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_vac_hh_id ON vacancies(hh_id);
CREATE INDEX IF NOT EXISTS idx_vac_status ON vacancies(status);
CREATE INDEX IF NOT EXISTS idx_vac_score ON vacancies(ai_score);
CREATE INDEX IF NOT EXISTS idx_vac_district ON vacancies(district);
CREATE INDEX IF NOT EXISTS idx_vac_verdict ON vacancies(ai_verdict);
CREATE INDEX IF NOT EXISTS idx_vac_remote ON vacancies(is_remote);
CREATE INDEX IF NOT EXISTS idx_vac_notified ON vacancies(notified);
CREATE INDEX IF NOT EXISTS idx_vac_source ON vacancies(source);
CREATE INDEX IF NOT EXISTS idx_vac_person ON vacancies(person);
CREATE INDEX IF NOT EXISTS idx_vac_search_name ON vacancies(search_name);
CREATE INDEX IF NOT EXISTS idx_vac_scrape_status ON vacancies(scrape_status);
CREATE INDEX IF NOT EXISTS idx_vac_user_id ON vacancies(user_id);
CREATE INDEX IF NOT EXISTS idx_vac_criteria_hash ON vacancies(hh_id, criteria_hash);
-- Covers findScrapePending/findPending's "WHERE person=? AND search_name=? AND ..."
-- shape directly, instead of relying on SQLite intersecting the single-column
-- person/search_name/scrape_status/verdict indexes above for every pipeline run.
CREATE INDEX IF NOT EXISTS idx_vac_person_search ON vacancies(person, search_name);
CREATE INDEX IF NOT EXISTS idx_tags_vid ON tags(vacancy_id);
CREATE INDEX IF NOT EXISTS idx_hist_vid ON history(vacancy_id);
CREATE INDEX IF NOT EXISTS idx_searches_user_id ON searches(user_id);
-- Indexes on columns added after the initial release (dedup_key, is_global, and the
-- user_vacancy_status table) are NOT here: this script runs unconditionally on every
-- boot (mode: always), including against an existing pre-upgrade DB where those
-- columns don't exist yet on the *first* boot after upgrading — an index statement
-- referencing a missing column fails immediately and prevents the app from starting
-- at all, before SchemaMigrator ever gets a chance to add the column. SchemaMigrator
-- creates these same indexes itself, right after it adds the column.
