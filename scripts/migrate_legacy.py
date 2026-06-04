#!/usr/bin/env python3
"""
Миграция данных из старой SQLite БД (hh-gui/vacancies.db) в новую структуру.
Запускать один раз при переходе на Spring Boot backend.

Usage:
    python3 migrate_legacy.py [--legacy-db /path/to/old.db] [--new-db /path/to/new.db]
"""
import sqlite3
import os
import sys
from datetime import datetime

LEGACY_DB = "/home/clawd/hh-gui/vacancies.db"
NEW_DB = "/home/clawd/hh-vacancy-manager/data/vacancies.db"


def migrate(legacy_path, new_path):
    if not os.path.exists(legacy_path):
        print(f"Legacy DB not found: {legacy_path}")
        sys.exit(1)

    legacy = sqlite3.connect(legacy_path)
    legacy.row_factory = sqlite3.Row

    # Создаём новую БД если нет
    new = sqlite3.connect(new_path)
    new.execute("PRAGMA journal_mode=WAL")

    # Создаём схему
    new.executescript("""
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
        CREATE INDEX IF NOT EXISTS idx_tags_vid ON tags(vacancy_id);
        CREATE INDEX IF NOT EXISTS idx_hist_vid ON history(vacancy_id);
    """)

    # Получаем существующие hh_id в новой БД
    existing = set(r[0] for r in new.execute("SELECT hh_id FROM vacancies WHERE hh_id != ''").fetchall())

    # Мигрируем вакансии
    rows = legacy.execute("SELECT * FROM vacancies").fetchall()
    imported = 0
    skipped = 0

    for r in rows:
        d = dict(r)
        hh_id = d.get("hh_id", "")

        if hh_id and hh_id in existing:
            skipped += 1
            continue

        now = datetime.utcnow().isoformat()
        new.execute("""
            INSERT OR IGNORE INTO vacancies
            (hh_id, title, company, salary_from, salary_to, currency, address, district,
             url, ai_score, description, status, rejection_reason, notes, applied_at,
             created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, (
            hh_id, d.get("title", ""), d.get("company", ""),
            d.get("salary_from", 0), d.get("salary_to", 0), d.get("currency", "RUR"),
            d.get("address", ""), d.get("district", ""), d.get("url", ""),
            d.get("ai_score", 0), d.get("description", ""),
            d.get("status", "new"), d.get("rejection_reason", ""),
            d.get("notes", ""), d.get("applied_at", ""),
            d.get("created_at", now), d.get("updated_at", now)
        ))

        if new.total_changes:
            # Получаем ID вставленной записи
            vid = new.execute("SELECT id FROM vacancies WHERE hh_id = ?", (hh_id,)).fetchone()
            if vid:
                vid = vid[0]
                # Мигрируем теги
                tags = legacy.execute("SELECT name FROM tags WHERE vacancy_id = ?", (d["id"],)).fetchall()
                for t in tags:
                    new.execute("INSERT INTO tags (vacancy_id, name) VALUES (?, ?)", (vid, t[0]))
                # Мигрируем историю
                hist = legacy.execute("SELECT action, details, created_at FROM history WHERE vacancy_id = ?", (d["id"],)).fetchall()
                for h in hist:
                    new.execute("INSERT INTO history (vacancy_id, action, details, created_at) VALUES (?,?,?,?)",
                                (vid, h[0], h[1], h[2]))
            imported += 1
        else:
            skipped += 1

    new.commit()

    # Статистика
    total_new = new.execute("SELECT COUNT(*) FROM vacancies").fetchone()[0]
    total_tags = new.execute("SELECT COUNT(*) FROM tags").fetchone()[0]
    total_hist = new.execute("SELECT COUNT(*) FROM history").fetchone()[0]

    legacy.close()
    new.close()

    print(f"Миграция завершена:")
    print(f"  Из старой БД: {len(rows)} вакансий")
    print(f"  Импортировано: {imported}")
    print(f"  Пропущено (дубли): {skipped}")
    print(f"  В новой БД: {total_new} вакансий, {total_tags} тегов, {total_hist} записей истории")


if __name__ == "__main__":
    legacy = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else LEGACY_DB
    new = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else NEW_DB

    if "--legacy-db" in sys.argv:
        idx = sys.argv.index("--legacy-db")
        legacy = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else LEGACY_DB
    if "--new-db" in sys.argv:
        idx = sys.argv.index("--new-db")
        new = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else NEW_DB

    os.makedirs(os.path.dirname(new), exist_ok=True)
    migrate(legacy, new)
