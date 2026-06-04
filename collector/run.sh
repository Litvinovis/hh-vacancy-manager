#!/bin/bash
# Единый пайплайн сбора вакансий
# Использование: ./run.sh [profile] [--sources hh,avito] [--no-notify]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PROFILE="${1:-mom}"
shift 2>/dev/null || true

# Загружаем переменные окружения из .env если есть
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

export PATH="/usr/bin:/usr/local/bin:$PATH"

cd "$PROJECT_DIR"

echo "=== $(date '+%Y-%m-%d %H:%M:%S') | Пайплайн | Профиль: $PROFILE ===" >&2

python3 collector/run_pipeline.py --profile "$PROFILE" "$@" 2>&1

echo "=== $(date '+%Y-%m-%d %H:%M:%S') | Готово ===" >&2
