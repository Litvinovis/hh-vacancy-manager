#!/usr/bin/env python3
"""
HH Vacancy Collector — RSS парсер вакансий с HH.ru.
Читает конфигурацию из YAML, собирает вакансии через RSS, сохраняет в SQLite.

Usage:
    python3 rss_parser.py                  # Сбор новых вакансий
    python3 rss_parser.py --dry-run        # Тест без сохранения
    python3 rss_parser.py --stats          # Статистика
    python3 rss_parser.py --config path    # Путь к конфигу
"""
import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree as ET
from urllib.parse import quote

import yaml


# ─── Пути ────────────────────────────────────────────────────────────────────

SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_DIR = SCRIPT_DIR.parent
CONFIG_PATH = PROJECT_DIR / "config" / "profiles" / "default.yaml"
DB_PATH = PROJECT_DIR / "data" / "vacancies.db"


def load_config(config_path: Path) -> dict:
    if not config_path.exists():
        print(f"❌ Конфиг не найден: {config_path}", file=sys.stderr)
        print("   Скопируйте config/profiles/default.yaml.example → config/profiles/default.yaml", file=sys.stderr)
        sys.exit(1)
    with open(config_path) as f:
        return yaml.safe_load(f)


# ─── Парсинг ────────────────────────────────────────────────────────────────

def clean_html(text: str) -> str:
    return re.sub(r"<[^>]+>", "", text).strip() if text else ""


def extract_salary(text: str) -> str:
    m = re.search(r"от\s*([\d\s]+)(?:\s*до\s*([\d\s]+))?\s*([₽$€]|[A-Z]{3})", text)
    if m:
        result = f"от {m.group(1).strip()}"
        if m.group(2):
            result += f" до {m.group(2).strip()}"
        result += f" {m.group(3)}"
        return result
    m = re.search(r"([\d\s]+)\s*—\s*([\d\s]+)\s*([₽$€]|[A-Z]{3})", text)
    if m:
        return f"{m.group(1).strip()} — {m.group(2).strip()} {m.group(3)}"
    m = re.search(r"([\d\s]+)\s*([₽$€])", text)
    if m:
        return f"{m.group(1).strip()} {m.group(2)}"
    return ""


def extract_company(text: str) -> str:
    m = re.search(r"Вакансия компании:\s*(.+?)(?:\n|Создана|$)", text)
    if m:
        return clean_html(m.group(1)).strip()
    return ""


def fetch_rss(query: str, area: int, schedule: str = "", salary_min: int = 0) -> str:
    params = [f"text={quote(query)}", f"area={area}"]
    if schedule:
        params.append(f"schedule={schedule}")
    if salary_min > 0:
        params.append(f"salary={salary_min}")
    params.append("per_page=20")
    url = f"https://hh.ru/search/vacancy/rss?{'&'.join(params)}"
    result = subprocess.run(
        ["curl", "-s", "-L",
         "-H", "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
         url],
        capture_output=True, text=True, timeout=30,
    )
    return result.stdout


def parse_rss(xml_text: str) -> list:
    vacancies = []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return vacancies
    channel = root.find("channel")
    if channel is None:
        return vacancies
    for item in channel.findall("item"):
        title = item.find("title").text or ""
        link = item.find("link").text or ""
        guid = item.find("guid").text or link
        pub_date = item.find("pubDate").text or ""
        description = item.find("description").text or ""
        vacancy_id = link.rstrip("/").split("/")[-1] if link else guid
        desc_clean = clean_html(description)
        vacancies.append({
            "id": vacancy_id,
            "title": title,
            "company": extract_company(description),
            "salary_text": extract_salary(desc_clean),
            "link": link,
            "description": desc_clean[:600],
            "published_at": pub_date,
        })
    return vacancies


# ─── База данных ─────────────────────────────────────────────────────────────

def init_db(db: sqlite3.Connection):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS vacancies (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            company TEXT,
            salary_text TEXT,
            link TEXT,
            description TEXT,
            published_at TEXT,
            first_seen_at TEXT NOT NULL,
            source_query TEXT,
            is_remote INTEGER DEFAULT 0,
            ai_score INTEGER DEFAULT 0,
            ai_verdict TEXT DEFAULT 'pending',
            ai_reason TEXT DEFAULT '',
            ai_analyzed_at TEXT,
            responded INTEGER DEFAULT 0,
            hidden INTEGER DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_vacancies_ai ON vacancies(ai_verdict, ai_score);
        CREATE INDEX IF NOT EXISTS idx_vacancies_published ON vacancies(published_at);
    """)


def save_vacancy(db: sqlite3.Connection, v: dict, query: str, is_remote: bool = False):
    db.execute("""
        INSERT OR IGNORE INTO vacancies
        (id, title, company, salary_text, link, description, published_at,
         first_seen_at, source_query, is_remote)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (v["id"], v["title"], v.get("company"), v.get("salary_text"),
          v["link"], v.get("description"), v.get("published_at"),
          datetime.now().isoformat(), query, 1 if is_remote else 0))


def get_new_ids(db: sqlite3.Connection, ids: list) -> set:
    existing = set()
    for i in range(0, len(ids), 500):
        chunk = ids[i:i + 500]
        placeholders = ",".join("?" * len(chunk))
        rows = db.execute(f"SELECT id FROM vacancies WHERE id IN ({placeholders})", chunk).fetchall()
        existing.update(r[0] for r in rows)
    return set(ids) - existing


# ─── Основной цикл ───────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="HH Vacancy Collector")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--stats", action="store_true")
    parser.add_argument("--config", type=Path, default=CONFIG_PATH)
    parser.add_argument("--db", type=Path, default=DB_PATH)
    args = parser.parse_args()

    config = load_config(args.config)
    search_cfg = config.get("search", {})
    remote_cfg = config.get("remote_search", {})

    db_path = args.db
    db_path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(db_path))
    db.row_factory = sqlite3.Row
    init_db(db)

    if args.stats:
        stats = {}
        stats["total"] = db.execute("SELECT COUNT(*) FROM vacancies").fetchone()[0]
        stats["pending"] = db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'").fetchone()[0]
        stats["approved"] = db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'yes'").fetchone()[0]
        stats["rejected"] = db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'no'").fetchone()[0]
        print(json.dumps(stats, ensure_ascii=False))
        return

    all_vacancies = []

    # Обычный поиск
    for query in search_cfg.get("queries", []):
        print(f"📡 RSS: '{query}'...", file=sys.stderr)
        xml = fetch_rss(
            query=query,
            area=search_cfg.get("area", 99),
            schedule=search_cfg.get("schedule", ""),
            salary_min=search_cfg.get("salary_min", 0),
        )
        vacancies = parse_rss(xml)
        for v in vacancies:
            v["_query"] = query
            v["_remote"] = False
        all_vacancies.extend(vacancies)
        time.sleep(1.5)

    # Удалённый поиск
    if remote_cfg.get("enabled", False):
        remote_exclude = [w.lower() for w in remote_cfg.get("exclude_words", [])]
        for query in remote_cfg.get("queries", []):
            print(f"📡 RSS (remote): '{query}'...", file=sys.stderr)
            xml = fetch_rss(
                query=query,
                area=remote_cfg.get("area", 113),
                schedule="remote",
                salary_min=remote_cfg.get("salary_min", 0),
            )
            vacancies = parse_rss(xml)
            for v in vacancies:
                v["_query"] = query
                v["_remote"] = True
                title_desc = (v["title"] + " " + v.get("description", "")).lower()
                v["_excluded"] = any(w in title_desc for w in remote_exclude)
            all_vacancies.extend([v for v in vacancies if not v["_excluded"]])
            time.sleep(1.5)

    # Дедупликация
    seen = {}
    for v in all_vacancies:
        if v["id"] not in seen:
            seen[v["id"]] = v
    unique = list(seen.values())

    # Фильтр по словам-исключениям
    exclude = [w.lower() for w in search_cfg.get("exclude_words", [])]
    if exclude:
        filtered = []
        for v in unique:
            if v.get("_remote"):
                filtered.append(v)
            else:
                text = v["title"].lower() + " " + v.get("description", "").lower()
                if not any(w in text for w in exclude):
                    filtered.append(v)
        unique = filtered

    new_ids = get_new_ids(db, [v["id"] for v in unique])
    new_vacancies = [v for v in unique if v["id"] in new_ids]

    print(f"📦 Собрано: {len(unique)} уникальных, новых: {len(new_vacancies)}", file=sys.stderr)

    if not args.dry_run:
        for v in new_vacancies:
            save_vacancy(db, v, v.pop("_query", ""), v.get("_remote", False))
        db.commit()

    clean = []
    for v in new_vacancies:
        cv = {k: val for k, val in v.items() if not k.startswith("_")}
        cv["is_remote"] = v.get("_remote", False)
        clean.append(cv)

    print(json.dumps({"new_count": len(new_vacancies), "new_vacancies": clean}, ensure_ascii=False))


if __name__ == "__main__":
    main()
