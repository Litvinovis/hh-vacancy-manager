#!/usr/bin/env python3
"""
Scoring Engine — движок скоринга вакансий.
"""

import re
import yaml
from pathlib import Path


class ScoringEngine:
    """Применяет правила скоринга к вакансиям."""

    def __init__(self, rules_file: str):
        self.rules = []
        self._load_rules(rules_file)

    def _load_rules(self, rules_file: str):
        path = Path(rules_file)
        if not path.exists():
            return
        with open(path) as f:
            data = yaml.safe_load(f)
        for r in data.get("rules", []):
            self.rules.append({
                "match": r.get("match", ""),
                "field": r.get("field", "title|description"),
                "score": r.get("score", 0),
                "reason": r.get("reason", ""),
            })

    def score(self, title: str, description: str, address: str,
              is_remote: bool = False) -> dict:
        """
        Скорит вакансию.

        Returns:
            dict с ключами: score (0-100), verdict (yes/no), reason (str)
        """
        score = 50
        reasons = []
        text = f"{title} {description} {address}".lower()

        for rule in self.rules:
            pattern = rule["match"]
            try:
                if re.search(pattern, text, re.IGNORECASE | re.UNICODE):
                    score += rule["score"]
                    if rule["reason"]:
                        reasons.append(rule["reason"])
            except re.error:
                if pattern.lower() in text:
                    score += rule["score"]
                    if rule["reason"]:
                        reasons.append(rule["reason"])

        score = max(0, min(100, score))
        verdict = "yes" if score >= 50 else "no"
        reason = "; ".join(reasons) if reasons else "Подходит по профилю"

        return {"score": score, "verdict": verdict, "reason": reason}
