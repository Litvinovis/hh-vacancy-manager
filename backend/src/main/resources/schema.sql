-- HH Vacancy Manager — Schema
-- Compatible with SQLite

CREATE TABLE IF NOT EXISTS vacancies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hh_id TEXT,
    person TEXT DEFAULT '',
    search_name TEXT DEFAULT '',
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
    UNIQUE(hh_id, person, search_name)
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
CREATE INDEX IF NOT EXISTS idx_tags_vid ON tags(vacancy_id);
CREATE INDEX IF NOT EXISTS idx_hist_vid ON history(vacancy_id);
