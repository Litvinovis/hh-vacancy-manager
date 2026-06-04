#!/bin/bash
# HH Vacancy Manager — Пайплайн сбора и отчёта
# Запускается по cron: 9,11,13,15,17,19,21

set -euo pipefail

PROJECT_DIR="/home/clawd/hh-vacancy-manager"
LOG_DIR="$PROJECT_DIR/data/logs"
PROFILE="${1:-mm}"

mkdir -p "$LOG_DIR"

LOG_FILE="$LOG_DIR/pipeline-$(date +%Y%m%d-%H%M%S).log"

{
  echo "=== $(date '+%Y-%m-%d %H:%M:%S') | Пайплайн | Профиль: $PROFILE ==="
  
  cd "$PROJECT_DIR"
  
  # Загружаем env из Hermes если .env проекта не содержит токен
  if grep -q '^# TELEGRAM_BOT_TOKEN=' "$PROJECT_DIR/.env" 2>/dev/null; then
    export TELEGRAM_BOT_TOKEN=$(grep '^TELEGRAM_BOT_TOKEN=*** "$HOME/.hermes/.env" 2>/dev/null | head -1 | cut -d= -f2-)
    export TELEGRAM_CHAT_ID=$(grep '^TELEGRAM_CHAT_ID=' "$HOME/.hermes/.env" 2>/dev/null | head -1 | cut -d= -f2-)
  fi
  
  python3 collector/run_pipeline.py --profile "$PROFILE" 2>&1
  
  echo "=== $(date '+%Y-%m-%d %H:%M:%S') | Готово ==="
} >> "$LOG_FILE" 2>&1

# Удаляем логи старше 30 дней
find "$LOG_DIR" -name "pipeline-*.log" -mtime +30 -delete 2>/dev/null || true
