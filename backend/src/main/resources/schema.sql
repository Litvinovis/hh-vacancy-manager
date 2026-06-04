-- HH Vacancy Manager — Schema
-- Compatible with SQLite

CREATE TABLE IF NOT EXISTS vacancies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hh_id TEXT UNIQUE,
    title TEXT NOT NULL DEFAULT '',
    company TEXT NOT NULL DEFAULT '',
    salary_from INTEGER DEFAULT 0,
    salary_to INTEGER DEFAULT 0,
    currency TEXT DEFAULT 'RUR',
    address TEXT DEFAULT '',
    district TEXT DEFAULT '',
    url TEXT DEFAULT '',
    ai_score INTEGER DEFAULT 0,
    ai_verdict TEXT DEFAULT 'pending',
    ai_reason TEXT DEFAULT '',
    description TEXT DEFAULT '',
    status TEXT NOT NULL DEFAULT 'new',
    rejection_reason TEXT DEFAULT '',
    notes TEXT DEFAULT '',
    applied_at TEXT DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
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
CREATE INDEX IF NOT EXISTS idx_tags_vid ON tags(vacancy_id);
CREATE INDEX IF NOT EXISTS idx_hist_vid ON history(vacancy_id);
