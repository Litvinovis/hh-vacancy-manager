#!/usr/bin/env python3
"""
Полная миграция данных в новую БД Spring Boot приложения.

Источники:
  1. hh_monitor.db — вакансии из RSS-парсера (основной источник)
  2. hh-gui/vacancies.db — старые данные из Python GUI (статусы, заметки, теги)

Новая БД: data/vacancies.db (используется Spring Boot)

Usage:
    python3 migrate_to_new_db.py [--hh-monitor-db path] [--legacy-gui-db path] [--new-db path]
"""
import sqlite3
import os
import sys
import re
from datetime import datetime

HH_MONITOR_DB = "/home/clawd/.hermes/scripts/hh_monitor/hh_monitor.db"
LEGACY_GUI_DB = "/home/clawd/hh-gui/vacancies.db"
NEW_DB = "/home/clawd/hh-vacancy-manager/data/vacancies.db"


def parse_salary(salary_text):
    """Парсит текст зарплаты в from/to/currency."""
    if not salary_text:
        return 0, 0, "RUR"
    text = salary_text.replace("\xa0", " ").replace(" ", "")
    cur = "RUR"
    if "₽" in text or "руб" in text.lower():
        cur = "RUR"
    elif "$" in text:
        cur = "USD"
    elif "€" in text:
        cur = "EUR"
    nums = re.findall(r"(\d+)", text)
    nums = [int(n) for n in nums]
    if "до" in salary_text and nums:
        return 0, nums[0], cur
    elif "от" in salary_text and nums:
        return nums[0], 0, cur
    elif len(nums) >= 2:
        return nums[0], nums[1], cur
    elif len(nums) == 1:
        return nums[0], 0, cur
    return 0, 0, cur


def migrate():
    # Создаём директорию для новой БД
    os.makedirs(os.path.dirname(NEW_DB), exist_ok=True)

    new = sqlite3.connect(NEW_DB)
    new.execute("PRAGMA journal_mode=WAL")

    # Создаём схему (та же что в schema.sql)
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

    # ── Шаг 1: Загружаем данные из legacy GUI (для сохранения статусов/заметок) ──
    legacy_data = {}
    if os.path.exists(LEGACY_GUI_DB):
        print(f"📂 Загрузка данных из legacy GUI: {LEGACY_GUI_DB}")
        legacy = sqlite3.connect(LEGACY_GUI_DB)
        legacy.row_factory = sqlite3.Row

        # Строим маппинг hh_id → данные
        rows = legacy.execute("SELECT * FROM vacancies").fetchall()
        for r in rows:
            d = dict(r)
            hh_id = d.get("hh_id", "")
            if hh_id:
                # Теги
                tags = [t[0] for t in legacy.execute(
                    "SELECT name FROM tags WHERE vacancy_id = ?", (d["id"],)).fetchall()]
                # История
                hist = legacy.execute(
                    "SELECT action, details, created_at FROM history WHERE vacancy_id = ?",
                    (d["id"],)).fetchall()
                legacy_data[hh_id] = {
                    "status": d.get("status", "new"),
                    "notes": d.get("notes", ""),
                    "rejection_reason": d.get("rejection_reason", ""),
                    "applied_at": d.get("applied_at", ""),
                    "tags": tags,
                    "history": hist,
                }

        legacy.close()
        print(f"   Загружено {len(legacy_data)} записей из legacy GUI")
    else:
        print(f"⚠️  Legacy GUI DB не найдена: {LEGACY_GUI_DB}")

    # ── Шаг 2: Мигрируем вакансии из hh_monitor ──
    if not os.path.exists(HH_MONITOR_DB):
        print(f"❌ HH Monitor DB не найдена: {HH_MONITOR_DB}")
        sys.exit(1)

    print(f"📂 Миграция из hh_monitor: {HH_MONITOR_DB}")
    hh = sqlite3.connect(HH_MONITOR_DB)
    hh.row_factory = sqlite3.Row

    rows = hh.execute("""
        SELECT id, title, company, salary_text, link, description,
               published_at, first_seen_at, source_query, is_remote,
               ai_score, ai_verdict, ai_reason, responded, hidden
        FROM vacancies
    """).fetchall()

    imported = 0
    skipped = 0
    now = datetime.utcnow().isoformat()

    for r in rows:
        hh_id = str(r["id"])

        # Проверяем дубли
        existing = new.execute("SELECT id FROM vacancies WHERE hh_id = ?", (hh_id,)).fetchone()
        if existing:
            skipped += 1
            continue

        salary_from, salary_to, currency = parse_salary(r["salary_text"])

        # Определяем статус
        status = "new"
        applied_at = ""
        if r["responded"]:
            status = "applied"
            applied_at = now
        elif r["hidden"]:
            status = "rejected"

        # Адрес / district
        address = ""
        district = ""
        if r["source_query"]:
            sq = r["source_query"]
            if "Шакша" in sq or "шакша" in sq.lower():
                district = "Шакша"

        # Переопределяем из legacy GUI если есть
        legacy = legacy_data.get(hh_id, {})
        if legacy:
            status = legacy.get("status", status)
            applied_at = legacy.get("applied_at", applied_at) or applied_at

        new.execute("""
            INSERT INTO vacancies
            (hh_id, title, company, salary_from, salary_to, currency, address, district,
             url, ai_score, ai_verdict, ai_reason, description, status,
             rejection_reason, notes, applied_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, (
            hh_id, r["title"] or "", r["company"] or "",
            salary_from, salary_to, currency,
            address, district, r["link"] or "",
            r["ai_score"] or 0, r["ai_verdict"] or "pending", r["ai_reason"] or "",
            r["description"] or "", status,
            legacy.get("rejection_reason", ""),
            legacy.get("notes", ""),
            applied_at,
            r["first_seen_at"] or now, now
        ))

        vid = new.execute("SELECT id FROM vacancies WHERE hh_id = ?", (hh_id,)).fetchone()[0]

        # Теги из legacy
        if legacy.get("tags"):
            for tag_name in legacy["tags"]:
                new.execute("INSERT INTO tags (vacancy_id, name) VALUES (?, ?)", (vid, tag_name))

        # История из legacy
        if legacy.get("history"):
            for h in legacy["history"]:
                new.execute(
                    "INSERT INTO history (vacancy_id, action, details, created_at) VALUES (?,?,?,?)",
                    (vid, h[0], h[1], h[2]))

        imported += 1

    hh.close()

    new.commit()

    # Статистика
    total = new.execute("SELECT COUNT(*) FROM vacancies").fetchone()[0]
    total_tags = new.execute("SELECT COUNT(*) FROM tags").fetchone()[0]
    total_hist = new.execute("SELECT COUNT(*) FROM history").fetchone()[0]
    by_status = new.execute(
        "SELECT status, COUNT(*) FROM vacancies GROUP BY status").fetchall()

    new.close()

    print(f"\n✅ Миграция завершена:")
    print(f"   Из hh_monitor: {len(rows)} вакансий")
    print(f"   Импортировано: {imported}")
    print(f"   Пропущено (дубли): {skipped}")
    print(f"   В новой БД: {total} вакансий, {total_tags} тегов, {total_hist} записей истории")
    print(f"   По статусам: {dict(by_status)}")


if __name__ == "__main__":
    hh_monitor = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else HH_MONITOR_DB
    legacy_gui = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else LEGACY_GUI_DB
    new_db = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else NEW_DB

    if "--hh-monitor-db" in sys.argv:
        idx = sys.argv.index("--hh-monitor-db")
        hh_monitor = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else HH_MONITOR_DB
    if "--legacy-gui-db" in sys.argv:
        idx = sys.argv.index("--legacy-gui-db")
        legacy_gui = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else LEGACY_GUI_DB
    if "--new-db" in sys.argv:
        idx = sys.argv.index("--new-db")
        new_db = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else NEW_DB

    migrate()
