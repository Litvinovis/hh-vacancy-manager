#!/usr/bin/env python3
"""
Telegram-каналы — парсер вакансий.

Читает посты из указанных Telegram-каналов и извлекает вакансии.
Требует telethon (pip install telethon) и API-ключ от Telegram.

Usage:
    python3 tg_parser.py --channels "channel1,channel2" --limit 50
    python3 tg_parser.py --config config/tg_channels.yaml

Setup:
    pip install telethon
    Получить api_id и api_hash на https://my.telegram.org
"""

import argparse
import json
import os
import re
import sys
import hashlib
from datetime import datetime, timedelta
from pathlib import Path

import yaml


# ─── Пути ────────────────────────────────────────────────────────────────────

SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_DIR = SCRIPT_DIR.parent
CONFIG_PATH = PROJECT_DIR / "config" / "tg_channels.yaml"
SESSION_PATH = PROJECT_DIR / "data" / "tg_session"


def load_config(config_path: Path) -> dict:
    if not config_path.exists():
        return {"channels": [], "api_id": None, "api_hash": None}
    with open(config_path) as f:
        return yaml.safe_load(f)


class TelegramParser:
    """Парсер вакансий из Telegram-каналов."""

    def __init__(self, api_id: int, api_hash: str, session_path: str = "tg_session"):
        self.api_id = api_id
        self.api_hash = api_hash
        self.session_path = session_path
        self.client = None

    async def connect(self):
        """Подключение к Telegram."""
        from telethon import TelegramClient

        self.client = TelegramClient(self.session_path, self.api_id, self.api_hash)
        await self.client.start()

    async def disconnect(self):
        if self.client:
            await self.client.disconnect()

    async def fetch_channels(self, channels: list, limit: int = 100,
                              days_back: int = 7) -> list:
        """
        Получает посты из каналов.

        Args:
            channels: список username каналов (без @)
            limit: максимум постов с канала
            days_back: сколько дней назад смотреть

        Returns:
            Список вакансий
        """
        if not self.client:
            await self.connect()

        vacancies = []
        since = datetime.now() - timedelta(days=days_back)

        for channel in channels:
            print(f"📡 Telegram: @{channel}...", file=sys.stderr)
            try:
                entity = await self.client.get_entity(channel)
                async for message in self.client.iter_messages(
                    entity, limit=limit, offset_date=since
                ):
                    if not message.text:
                        continue

                    vacancy = self._parse_message(message, channel)
                    if vacancy:
                        vacancies.append(vacancy)

            except Exception as e:
                print(f"⚠️ Ошибка @{channel}: {e}", file=sys.stderr)

        return vacancies

    def _parse_message(self, message, channel: str) -> dict:
        """Парсит сообщение и извлекает данные вакансии."""
        text = message.text or ""

        # Пропускаем короткие сообщения (не вакансии)
        if len(text) < 30:
            return None

        # Ищем ключевые слова вакансии
        vacancy_keywords = [
            "вакансия", "ищем", "требуется", "приглашаем", "работа",
            "зарплата", "з/п", "₽", "руб", "оффер", "join us",
            "hiring", ".position", "работодатель",
        ]

        text_lower = text.lower()
        if not any(kw in text_lower for kw in vacancy_keywords):
            return None

        # Извлекаем данные
        title = self._extract_title(text)
        company = self._extract_company(text)
        salary = self._extract_salary(text)
        address = self._extract_address(text)
        contacts = self._extract_contacts(text)

        # Генерируем уникальный ID
        msg_hash = hashlib.md5(
            f"{channel}_{message.id}_{text[:100]}".encode()
        ).hexdigest()[:16]

        return {
            "id": f"tg_{msg_hash}",
            "title": title,
            "company": company,
            "salary_text": salary,
            "address": address,
            "contacts": contacts,
            "description": text[:1000],
            "published_at": message.date.isoformat() if message.date else "",
            "link": f"https://t.me/{channel}/{message.id}",
            "channel": channel,
            "source": "telegram",
        }

    def _extract_title(self, text: str) -> str:
        """Извлекает заголовок вакансии."""
        # Первая строка обычно — заголовок
        lines = [l.strip() for l in text.split("\n") if l.strip()]
        if lines:
            title = lines[0]
            # Обрезаем если слишком длинный
            if len(title) > 120:
                title = title[:117] + "..."
            return title
        return ""

    def _extract_company(self, text: str) -> str:
        """Извлекает название компании."""
        patterns = [
            r"(?:компания|организация|фирма|работодатель)[\s:]*([^\n,]+)",
            r"(?:в|от)\s+([А-ЯA-Z][^\n,]{2,50}?)(?:\s+требуется|\s+ищем|\s*$)",
        ]
        for pattern in patterns:
            m = re.search(pattern, text, re.IGNORECASE)
            if m:
                return m.group(1).strip()
        return ""

    def _extract_salary(self, text: str) -> str:
        """Извлекает зарплату."""
        patterns = [
            r"(?:зарплата|з/п|оклад|ставка)[\s:]*([0-9\s]+[₽$€]?(?:\s*-\s*[0-9\s]+[₽$€]?)?)",
            r"([0-9]{2,3}[\s]?[0-9]{3})\s*[₽$€]",
            r"(?:от|до)\s+([0-9\s]+[₽$€])",
        ]
        for pattern in patterns:
            m = re.search(pattern, text, re.IGNORECASE)
            if m:
                return m.group(1).strip()
        return ""

    def _extract_address(self, text: str) -> str:
        """Извлекает адрес."""
        patterns = [
            r"(?:адрес|метро|район|локация)[\s:]*([^\n,]+)",
            r"(?:Уфа|Москва|СПб|Екатеринбург)[^\n,]{0,50}",
        ]
        for pattern in patterns:
            m = re.search(pattern, text, re.IGNORECASE)
            if m:
                return m.group(1).strip() if m.lastindex else m.group(0).strip()
        return ""

    def _extract_contacts(self, str) -> list:
        """Извлекает контакты."""
        contacts = []
        # Телефон
        phones = re.findall(r"[\+]?[78][\s\-]?[\(]?\d{3}[\)]?[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}", text)
        contacts.extend(phones)
        # Email
        emails = re.findall(r"[\w\.\-]+@[\w\.\-]+\.\w+", text)
        contacts.extend(emails)
        # Telegram
        tgs = re.findall(r"@[\w_]{5,}", text)
        contacts.extend(tgs)
        return contacts


async def main():
    parser = argparse.ArgumentParser(description="Telegram-каналы — парсер вакансий")
    parser.add_argument("--channels", help="Список каналов через запятую")
    parser.add_argument("--limit", type=int, default=100, help="Постов с канала")
    parser.add_argument("--days", type=int, default=7, help="Дней назад")
    parser.add_argument("--config", type=Path, default=CONFIG_PATH)
    parser.add_argument("--output", choices=["json", "text"], default="json")
    args = parser.parse_args()

    config = load_config(args.config)
    api_id = config.get("api_id")
    api_hash = config.get("api_hash")

    if not api_id or not api_hash:
        print("❌ Не указаны api_id и api_hash", file=sys.stderr)
        print("   Создайте config/tg_channels.yaml с ключами:", file=sys.stderr)
        print("   api_id: 12345", file=sys.stderr)
        print("   api_hash: abcdef...", file=sys.stderr)
        sys.exit(1)

    channels = args.channels.split(",") if args.channels else config.get("channels", [])
    if not channels:
        print("❌ Не указаны каналы", file=sys.stderr)
        sys.exit(1)

    tg = TelegramParser(api_id, api_hash)
    try:
        await tg.connect()
        vacancies = await tg.fetch_channels(
            channels=channels,
            limit=args.limit,
            days_back=args.days,
        )

        if args.output == "json":
            print(json.dumps(vacancies, ensure_ascii=False, indent=2))
        else:
            for v in vacancies:
                print(f"  {v['title']}")
                print(f"  🏢 {v.get('company', '—')} | 💰 {v.get('salary_text', '—')}")
                print(f"  📢 @{v.get('channel', '—')}")
                print(f"  🔗 {v['link']}")
                print()
    finally:
        await tg.disconnect()


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
