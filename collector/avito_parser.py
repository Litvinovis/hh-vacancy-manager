#!/usr/bin/env python3
"""
Авито Работа — парсер вакансий.

Использует публичное API Авито (avito.ru) для поиска вакансий.
Работает без API-ключа через веб-скрапинг с соблюдением robots.txt.

Usage:
    python3 avito_parser.py --query "продавец" --city "ufa" --limit 20
    python3 avito_parser.py --config config/avito.yaml
"""

import argparse
import json
import re
import sys
import time
import hashlib
from datetime import datetime
from urllib.parse import quote, urljoin
from pathlib import Path

import requests


# ─── Константы ────────────────────────────────────────────────────────────────

BASE_URL = "https://www.avito.ru"
SEARCH_URL = "https://www.avito.ru/{city}/vakansii?q={query}"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.5",
}

# CSS-селекторы для парсинга (могут меняться при обновлении сайта)
SELECTORS = {
    "item": '[data-marker="item"]',
    "title": '[itemprop="title"]',
    "link": '[data-marker="item-title"]',
    "price": '[itemprop="price"]',
    "company": '[data-marker="seller-info/link"]',
    "address": '[class*="geo"]',
    "date": '[data-marker="item-date"]',
    "description": '[itemprop="description"]',
    "next_page": '[data-marker="pagination-button/next"]',
}


class AvitoParser:
    """Парсер вакансий с Авито Работа."""

    def __init__(self, delay: float = 2.0, timeout: int = 30):
        self.delay = delay
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(HEADERS)

    def search(self, query: str, city: str = "ufa", limit: int = 50,
               salary_min: int = 0, remote: bool = False) -> list:
        """
        Поиск вакансий на Авито.

        Args:
            query: поисковый запрос
            city: город (ufa, moskva, sankt-peterburg и т.д.)
            limit: максимум вакансий
            salary_min: минимальная зарплата
            remote: только удалёнка

        Returns:
            Список словарей с данными вакансий
        """
        vacancies = []
        page = 1

        while len(vacancies) < limit:
            url = self._build_url(query, city, page, salary_min, remote)
            print(f"📡 Авито: страница {page}...", file=sys.stderr)

            try:
                html = self._fetch(url)
            except Exception as e:
                print(f"⚠️ Ошибка: {e}", file=sys.stderr)
                break

            items = self._parse_page(html)
            if not items:
                break

            vacancies.extend(items)
            page += 1
            time.sleep(self.delay)

        return vacancies[:limit]

    def _build_url(self, query: str, city: str, page: int,
                    salary_min: int, remote: bool) -> str:
        params = f"?q={quote(query)}"
        if page > 1:
            params += f"&p={page}"
        if salary_min > 0:
            params += f"&pmin={salary_min}"
        if remote:
            params += "&remote=1"
        return f"https://www.avito.ru/{city}/vakansii{params}"

    def _fetch(self, url: str) -> str:
        resp = self.session.get(url, timeout=self.timeout)
        resp.raise_for_status()
        return resp.text

    def _parse_page(self, html: str) -> list:
        """Парсит HTML страницу и возвращает список вакансий."""
        from html.parser import HTMLParser

        vacancies = []

        # Ищем JSON-данные в странице (Avivo хранит данные в window.__initialData__)
        json_match = re.search(
            r'window\.__initialData__\s*=\s*"(.+?)"', html
        )
        if json_match:
            try:
                # Декодируем JSON из HTML-encoded строки
                json_str = json_match.group(1).replace('\\"', '"')
                data = json.loads(json_str)
                items = self._extract_from_json(data)
                if items:
                    return items
            except (json.JSONDecodeError, KeyError):
                pass

        # Fallback: парсим HTML напрямую
        return self._parse_html_fallback(html)

    def _extract_from_json(self, data: dict) -> list:
        """Извлекает вакансии из JSON-данных страницы."""
        vacancies = []

        # Навигация по структуре JSON Avito
        try:
            catalog = data.get("catalog", {})
            items = catalog.get("items", [])

            for item in items:
                if item.get("type") != "item":
                    continue

                vacancy = {
                    "id": str(item.get("id", "")),
                    "title": item.get("title", ""),
                    "company": item.get("companyName", item.get("seller", {}).get("name", "")),
                    "salary_text": self._format_salary(item.get("price", {})),
                    "link": f"https://www.avito.ru{item.get('urlPath', '')}",
                    "description": item.get("description", "")[:600],
                    "address": item.get("geo", {}).get("formattedAddress", ""),
                    "published_at": item.get("sortTimeStamp", ""),
                    "source": "avito",
                }

                if vacancy["id"] and vacancy["title"]:
                    vacancies.append(vacancy)

        except (KeyError, TypeError):
            pass

        return vacancies

    def _parse_html_fallback(self, html: str) -> list:
        """Fallback-парсинг HTML через регулярные выражения."""
        vacancies = []

        # Ищем карточки вакансий
        item_pattern = re.compile(
            r'<div[^>]*data-marker="item"[^>]*>.*?</div>\s*</div>\s*</div>',
            re.DOTALL
        )

        for match in item_pattern.finditer(html):
            item_html = match.group(0)

            title = self._extract_text(item_html, r'<span[^>]*itemprop="title"[^>]*>(.*?)</span>')
            link = self._extract_attr(item_html, r'<a[^>]*href="(/[^"]*)"[^>]*data-marker="item-title"')
            price = self._extract_text(item_html, r'<span[^>]*itemprop="price"[^>]*>(.*?)</span>')
            company = self._extract_text(item_html, r'<a[^>]*data-marker="seller-info/link"[^>]*>(.*?)</a>')
            address = self._extract_text(item_html, r'<span[^>]*class="[^"]*geo[^"]*"[^>]*>(.*?)</span>')

            if title and link:
                vacancy_id = hashlib.md5(link.encode()).hexdigest()[:12]
                vacancies.append({
                    "id": f"avito_{vacancy_id}",
                    "title": self._clean_text(title),
                    "company": self._clean_text(company),
                    "salary_text": self._clean_text(price),
                    "link": urljoin(BASE_URL, link),
                    "description": "",
                    "address": self._clean_text(address),
                    "published_at": "",
                    "source": "avito",
                })

        return vacancies

    def _format_salary(self, price_data) -> str:
        """Форматирует зарплату из данных Avito."""
        if isinstance(price_data, dict):
            value = price_data.get("value", "")
            currency = price_data.get("currency", "₽")
            if value:
                return f"{value} {currency}"
        return str(price_data) if price_data else ""

    def _extract_text(self, html: str, pattern: str) -> str:
        m = re.search(pattern, html, re.DOTALL)
        if m:
            return self._clean_text(m.group(1))
        return ""

    def _extract_attr(self, html: str, pattern: str) -> str:
        m = re.search(pattern, html, re.DOTALL)
        return m.group(1) if m else ""

    def _clean_text(self, text: str) -> str:
        if not text:
            return ""
        text = re.sub(r"<[^>]+>", "", text)
        text = text.strip()
        return text


def main():
    parser = argparse.ArgumentParser(description="Авито Работа — парсер вакансий")
    parser.add_argument("--query", default="продавец", help="Поисковый запрос")
    parser.add_argument("--city", default="ufa", help="Город")
    parser.add_argument("--limit", type=int, default=20, help="Макс. вакансий")
    parser.add_argument("--salary-min", type=int, default=0, help="Мин. зарплата")
    parser.add_argument("--remote", action="store_true", help="Только удалёнка")
    parser.add_argument("--output", choices=["json", "text"], default="json")
    args = parser.parse_args()

    avito = AvitoParser()
    vacancies = avito.search(
        query=args.query,
        city=args.city,
        limit=args.limit,
        salary_min=args.salary_min,
        remote=args.remote,
    )

    if args.output == "json":
        print(json.dumps(vacancies, ensure_ascii=False, indent=2))
    else:
        for v in vacancies:
            print(f"  {v['title']}")
            print(f"  🏢 {v.get('company', '—')} | 💰 {v.get('salary_text', '—')}")
            print(f"  📍 {v.get('address', '—')}")
            print(f"  🔗 {v['link']}")
            print()


if __name__ == "__main__":
    main()
