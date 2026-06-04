#!/usr/bin/env python3
"""
Единый пайплайн сбора вакансий.

Запускает все парсеры (HH.ru, Авито, Telegram),
сохраняет в БД, применяет скоринг, отправляет отчёт в Telegram.

Usage:
    python3 run_pipeline.py                    # Полный цикл
    python3 run_pipeline.py --dry-run          # Тест без сохранения
    python3 run_pipeline.py --profile mom      # Конкретный профиль
    python3 run_pipeline.py --sources hh,avito # Конкретные источники
"""

import argparse
import json
import os
import sqlite3
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

import yaml

from scoring_engine import ScoringEngine


# ─── Пути ────────────────────────────────────────────────────────────────────

SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_DIR = SCRIPT_DIR.parent
CONFIG_PATH = PROJECT_DIR / "config" / "profiles" / "default.yaml"
RULES_PATH = PROJECT_DIR / "config" / "rules.yaml"
DB_PATH = PROJECT_DIR / "data" / "vacancies.db"

# Пути к парсерам
PARSERS = {
    "hh": SCRIPT_DIR / "rss_parser.py",
    "avito": SCRIPT_DIR / "avito_parser.py",
    "telegram": SCRIPT_DIR / "tg_parser.py",
}


# ─── Загрузка конфигурации ───────────────────────────────────────────────────

def load_config(path: Path) -> dict:
    if not path.exists():
        print(f"❌ Конфиг не найден: {path}", file=sys.stderr)
        sys.exit(1)
    with open(path) as f:
        return yaml.safe_load(f)


# ─── База данных ──────────────────────────────────────────────────────────────

def init_db(db: sqlite3.Connection):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS vacancies (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            company TEXT,
            salary_text TEXT,
            link TEXT,
            description TEXT,
            address TEXT,
            published_at TEXT,
            first_seen_at TEXT NOT NULL,
            source TEXT DEFAULT 'hh',
            source_query TEXT,
            is_remote INTEGER DEFAULT 0,
            ai_score INTEGER DEFAULT 0,
            ai_verdict TEXT DEFAULT 'pending',
            ai_reason TEXT DEFAULT '',
            ai_analyzed_at TEXT,
            responded INTEGER DEFAULT 0,
            hidden INTEGER DEFAULT 0,
            notified INTEGER DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_vacancies_ai ON vacancies(ai_verdict, ai_score);
        CREATE INDEX IF NOT EXISTS idx_vacancies_source ON vacancies(source);
        CREATE INDEX IF NOT EXISTS idx_vacancies_notified ON vacancies(notified);
    """)


def save_vacancy(db: sqlite3.Connection, v: dict):
    db.execute("""
        INSERT OR IGNORE INTO vacancies
        (id, title, company, salary_text, link, description, address,
         published_at, first_seen_at, source, source_query, is_remote)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        v["id"], v.get("title", ""), v.get("company", ""),
        v.get("salary_text", ""), v.get("link", ""),
        v.get("description", "")[:600], v.get("address", ""),
        v.get("published_at", ""), datetime.now().isoformat(),
        v.get("source", "hh"), v.get("query", ""),
        1 if v.get("is_remote") else 0,
    ))


# ─── Запуск парсеров ─────────────────────────────────────────────────────────

def run_hh_parser(queries: list, area: int, schedule: str,
                   salary_min: int, remote: bool, remote_queries: list,
                   remote_area: int, dry_run: bool) -> list:
    """Запускает HH.ru RSS парсер."""
    print("📡 Запуск HH.ru парсера...", file=sys.stderr)

    all_vacancies = []

    # Обычный поиск
    for query in queries:
        try:
            result = subprocess.run(
                [sys.executable, str(PARSERS["hh"]),
                 "--query", query,
                 "--area", str(area),
                 "--schedule", schedule,
                 "--salary-min", str(salary_min),
                 "--output", "json"] + (["--dry-run"] if dry_run else []),
                capture_output=True, text=True, timeout=120,
            )
            if result.returncode == 0 and result.stdout.strip():
                data = json.loads(result.stdout)
                vacancies = data if isinstance(data, list) else data.get("new_vacancies", [])
                for v in vacancies:
                    v["source"] = "hh"
                    v["query"] = query
                    v["is_remote"] = False
                all_vacancies.extend(vacancies)
        except Exception as e:
            print(f"⚠️ HH.ru ошибка: {e}", file=sys.stderr)

        time.sleep(1.5)

    # Удалённый поиск
    if remote and remote_queries:
        for query in remote_queries:
            try:
                result = subprocess.run(
                    [sys.executable, str(PARSERS["hh"]),
                     "--query", query,
                     "--area", str(remote_area),
                     "--schedule", "remote",
                     "--output", "json"] + (["--dry-run"] if dry_run else []),
                    capture_output=True, text=True, timeout=120,
                )
                if result.returncode == 0 and result.stdout.strip():
                    data = json.loads(result.stdout)
                    vacancies = data if isinstance(data, list) else data.get("new_vacancies", [])
                    for v in vacancies:
                        v["source"] = "hh"
                        v["query"] = query
                        v["is_remote"] = True
                    all_vacancies.extend(vacancies)
            except Exception as e:
                print(f"⚠️ HH.ru remote ошибка: {e}", file=sys.stderr)

            time.sleep(1.5)

    return all_vacancies


def run_avito_parser(queries: list, cities: list, salary_min: int,
                      dry_run: bool) -> list:
    """Запускает Авито парсер."""
    print("📡 Запуск Авито парсера...", file=sys.stderr)

    all_vacancies = []

    for city in cities:
        for query in queries:
            try:
                result = subprocess.run(
                    [sys.executable, str(PARSERS["avito"]),
                     "--query", query,
                     "--city", city,
                     "--limit", "20",
                     "--salary-min", str(salary_min),
                     "--output", "json"],
                    capture_output=True, text=True, timeout=120,
                )
                if result.returncode == 0 and result.stdout.strip():
                    vacancies = json.loads(result.stdout)
                    for v in vacancies:
                        v["source"] = "avito"
                        v["query"] = query
                    all_vacancies.extend(vacancies)
            except Exception as e:
                print(f"⚠️ Авито ошибка: {e}", file=sys.stderr)

            time.sleep(2.0)

    return all_vacancies


# ─── Скоринг ─────────────────────────────────────────────────────────────────

def score_vacancies(db: sqlite3.Connection, scorer: ScoringEngine):
    """Применяет скоринг к непроанализированным вакансиям."""
    rows = db.execute(
        "SELECT id, title, description, address, is_remote FROM vacancies WHERE ai_verdict = 'pending'"
    ).fetchall()

    scored = 0
    for row in rows:
        result = scorer.score(
            title=row[1] or "",
            description=row[2] or "",
            address=row[3] or "",
            is_remote=bool(row[4]),
        )
        db.execute("""
            UPDATE vacancies SET ai_score=?, ai_verdict=?, ai_reason=?, ai_analyzed_at=?
            WHERE id=?
        """, (result.score, result.verdict, result.reason, datetime.now().isoformat(), row[0]))
        scored += 1

    db.commit()
    print(f"🎯 Скоринг применён к {scored} вакансиям", file=sys.stderr)
    return scored


# ─── Отчёт в Telegram ─────────────────────────────────────────────────────────

def send_telegram_report(db: sqlite3.Connection, config: dict, profile: str):
    """Отправляет отчёт о новых вакансиях в Telegram."""
    profile_cfg = config.get("profiles", {}).get(profile, {})
    notif_cfg = profile_cfg.get("notifications", {})

    if not notif_cfg.get("enabled", False):
        print("ℹ️ Уведомления выключены", file=sys.stderr)
        return

    chat_id = notif_cfg.get("telegram_chat_id", "")
    min_score = notif_cfg.get("min_score", 60)
    max_per_day = notif_cfg.get("max_per_day", 10)

    if not chat_id:
        print("⚠️ Не указан telegram_chat_id", file=sys.stderr)
        return

    # Получаем лучшие неотправленные вакансии
    rows = db.execute("""
        SELECT id, title, company, salary_text, link, ai_score, ai_reason, source
        FROM vacancies
        WHERE ai_verdict = 'yes' AND ai_score >= ? AND notified = 0
        ORDER BY ai_score DESC, published_at DESC
        LIMIT ?
    """, (min_score, max_per_day)).fetchall()

    if not rows:
        print("ℹ️ Нет новых подходящих вакансий", file=sys.stderr)
        return

    # Формируем сообщение
    lines = [f"🔍 *Новые вакансии для {profile_cfg.get('name', profile)}*\n"]

    for i, row in enumerate(rows, 1):
        title, company, salary, link, score, reason, source = row[1], row[2], row[3], row[4], row[5], row[6], row[7]

        emoji = "🟢" if score >= 80 else "🟡" if score >= 60 else "🟠"
        source_emoji = {"hh": "📋", "avito": "🟠", "telegram": "📢"}.get(source, "📄")

        lines.append(
            f"{i}. {emoji} *[{score}%]* {source_emoji} {title}\n"
            f"   🏢 {company or '—'} | 💰 {salary or 'не указана'}\n"
            f"   💡 {reason}\n"
            f"   🔗 {link}\n"
        )

    message = "\n".join(lines)

    # Отправляем через Telegram Bot API
    bot_token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if bot_token:
        import urllib.request
        import urllib.parse

        url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
        data = urllib.parse.urlencode({
            "chat_id": chat_id,
            "text": message,
            "parse_mode": "Markdown",
            "disable_web_page_preview": True,
        }).encode()

        try:
            urllib.request.urlopen(url, data=data, timeout=30)
            print(f"✅ Отчёт отправлен в Telegram ({len(rows)} вакансий)", file=sys.stderr)

            # Помечаем как отправленные
            for row in rows:
                db.execute("UPDATE vacancies SET notified = 1 WHERE id = ?", (row[0],))
            db.commit()
        except Exception as e:
            print(f"❌ Ошибка отправки: {e}", file=sys.stderr)
    else:
        print("⚠️ TELEGRAM_BOT_TOKEN не задан", file=sys.stderr)
        print(f"--- Сообщение ---\n{message}", file=sys.stderr)


# ─── Основной цикл ───────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Единый пайплайн сбора вакансий")
    parser.add_argument("--profile", default="mom", help="Профиль поиска")
    parser.add_argument("--sources", default="hh,avito", help="Источники (hh,avito,telegram)")
    parser.add_argument("--dry-run", action="store_true", help="Тест без сохранения")
    parser.add_argument("--no-notify", action="store_true", help="Не отправлять уведомления")
    args = parser.parse_args()

    config = load_config(CONFIG_PATH)
    profile_cfg = config.get("profiles", {}).get(args.profile, {})

    if not profile_cfg:
        print(f"❌ Профиль '{args.profile}' не найден", file=sys.stderr)
        sys.exit(1)

    search_cfg = profile_cfg.get("search", {})
    remote_cfg = profile_cfg.get("remote_search", {})
    sources_cfg = config.get("sources", {})

    db_path = DB_PATH
    db_path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(db_path))
    db.row_factory = sqlite3.Row
    init_db(db)

    scorer = ScoringEngine(str(RULES_PATH))

    all_vacancies = []
    sources = args.sources.split(",")

    # ── Сбор данных ──

    if "hh" in sources and sources_cfg.get("hh_rss", {}).get("enabled", True):
        hh_vacancies = run_hh_parser(
            queries=search_cfg.get("queries", []),
            area=search_cfg.get("area", 99),
            schedule=search_cfg.get("schedule", ""),
            salary_min=search_cfg.get("salary_min", 0),
            remote=remote_cfg.get("enabled", False),
            remote_queries=remote_cfg.get("queries", []),
            remote_area=remote_cfg.get("area", 113),
            dry_run=args.dry_run,
        )
        all_vacancies.extend(hh_vacancies)
        print(f"   HH.ru: {len(hh_vacancies)} вакансий", file=sys.stderr)

    if "avito" in sources and sources_cfg.get("avito", {}).get("enabled", True):
        avito_vacancies = run_avito_parser(
            queries=search_cfg.get("queries", []),
            cities=sources_cfg.get("avito", {}).get("cities", ["ufa"]),
            salary_min=search_cfg.get("salary_min", 0),
            dry_run=args.dry_run,
        )
        all_vacancies.extend(avito_vacancies)
        print(f"   Авито: {len(avito_vacancies)} вакансий", file=sys.stderr)

    # ── Сохранение ──

    if not args.dry_run:
        saved = 0
        for v in all_vacancies:
            try:
                save_vacancy(db, v)
                saved += 1
            except Exception as e:
                pass  # Дубликат или ошибка
        db.commit()
        print(f"💾 Сохранено: {saved} новых вакансий", file=sys.stderr)

        # ── Скоринг ──
        scored = score_vacancies(db, scorer)

        # ── Уведомления ──
        if not args.no_notify:
            send_telegram_report(db, config, args.profile)

    # Статистика
    stats = {
        "total": db.execute("SELECT COUNT(*) FROM vacancies").fetchone()[0],
        "pending": db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'pending'").fetchone()[0],
        "approved": db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'yes'").fetchone()[0],
        "rejected": db.execute("SELECT COUNT(*) FROM vacancies WHERE ai_verdict = 'no'").fetchone()[0],
    }
    print(f"📊 Статистика: {stats}", file=sys.stderr)

    db.close()


if __name__ == "__main__":
    main()
