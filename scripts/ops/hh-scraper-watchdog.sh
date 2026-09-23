#!/bin/bash
# Notify-only watchdog для hh-scraper (:8095) — сайдкара, через который идёт весь скрейп
# карточек hh.ru (18.09.2026, по образцу tg-scraper-watchdog).
# Как и у соседа, сам ничего не перезапускает: у скрапера в профиле живут куки обхода
# DDoS-Guard, и жёсткий рестарт менял бы транзитный сбой на потерю прогретого профиля.
# Эскалация только после серии неудач — одиночный блип (перезапуск podkop, тик сети) молчит.
#
# Вторая проверка (23.09.2026): сайдкар может отвечать на /health и при этом не скачать
# ни одной страницы — DDoS-Guard отдаёт челлендж, и каждая загрузка заканчивается
# «blocked». Раньше это было видно только по пустым шагам пайплайна. Теперь /health
# отдаёт счётчики, и если свежие попытки есть, а успешных нет дольше BLOCK_ALERT_HOURS
# при растущем числе блокировок — пишем владельцу один раз до восстановления.
# Файл хранится в репозитории: scripts/ops/, ставится деплоем в /usr/local/bin.
set -euo pipefail

STATE_DIR="/var/lib/hh-scraper-watchdog"
STATE_FILE="$STATE_DIR/streak"
BLOCK_STATE="$STATE_DIR/blocked"   # «blockedCount lastAlerted(0/1)» с прошлой проверки
THRESHOLD=3
BLOCK_ALERT_HOURS=3
ENV_FILE="/opt/hh-gui/.env"

mkdir -p "$STATE_DIR"
streak=0
[ -f "$STATE_FILE" ] && streak=$(cat "$STATE_FILE")

notify() {
    local BOT_TOKEN CHAT_ID
    BOT_TOKEN=$(grep -oP 'TELEGRAM_BOT_TOKEN=\K.*' "$ENV_FILE")
    CHAT_ID=$(grep -oP 'TELEGRAM_CHAT_ID=\K.*' "$ENV_FILE")
    curl -s "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
        --data-urlencode "chat_id=${CHAT_ID}" \
        --data-urlencode "text=$1" > /dev/null || true
}

# Сервис может быть намеренно остановлен (обслуживание, деплой) — это не повод будить владельца
if ! systemctl is-active --quiet hh-scraper; then
    echo 0 > "$STATE_FILE"
    exit 0
fi

if ! health=$(curl -sf --max-time 10 http://127.0.0.1:8095/health); then
    streak=$((streak + 1))
    echo "$streak" > "$STATE_FILE"
    if [ "$streak" -eq "$THRESHOLD" ]; then
        notify "⚠️ hh-scraper: сайдкар скрейпа (:8095) не отвечает ${streak} проверок подряд (~$((streak*5)) мин). Пайплайн без него собирает карточки, но не читает описания вакансий — анализ встанет. Смотреть: journalctl -u hh-scraper -n 50. Сам не перезапускаю — в профиле куки обхода DDoS-Guard."
    fi
    exit 0
fi
echo 0 > "$STATE_FILE"

# ── блокировка при живом процессе ──
# Старые версии сайдкара счётчиков не отдают — тогда просто выходим.
blocked=$(echo "$health" | jq -r '.blockedCount // empty' 2>/dev/null || true)
[ -z "$blocked" ] && exit 0
last_ok=$(echo "$health" | jq -r '.lastOkAt // .startedAt')
prev_blocked=0; alerted=0
[ -f "$BLOCK_STATE" ] && read -r prev_blocked alerted < "$BLOCK_STATE" || true
# Счётчики живут в памяти процесса — после рестарта начинаются с нуля.
[ "$blocked" -lt "$prev_blocked" ] && prev_blocked=0

age_h=$(( ( $(date +%s) - $(date -d "$last_ok" +%s) ) / 3600 ))
if [ "$age_h" -ge "$BLOCK_ALERT_HOURS" ] && [ "$blocked" -gt "$prev_blocked" ]; then
    if [ "$alerted" -eq 0 ]; then
        notify "⚠️ hh-scraper: процесс жив, но ${age_h} ч ни одной успешной загрузки, а блокировок DDoS-Guard прибавилось ($prev_blocked → $blocked). Описания вакансий не скачиваются. Смотреть: curl -s 127.0.0.1:8095/health; journalctl -u hh-scraper -n 50."
        alerted=1
    fi
elif [ "$age_h" -lt "$BLOCK_ALERT_HOURS" ]; then
    alerted=0   # снова есть успешные загрузки — следующая блокировка снова достойна алерта
fi
echo "$blocked $alerted" > "$BLOCK_STATE"
