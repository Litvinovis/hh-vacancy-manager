#!/bin/bash
# Notify-only watchdog for tg-scraper's persistent Telegram Web session (see
# GET /health/session in tg-scraper/server.js). Deliberately does NOT restart
# tg-scraper itself: a SIGKILL before its SIGTERM handler finishes flushing
# IndexedDB has a documented case of losing the logged-in session, so an
# auto-restart here would trade a transient hang for a much worse outage.
# Only escalates after a sustained streak, not a single blip — see THRESHOLD.
#
# Прогрев (18.09.2026): /health/session отвечает no_active_session и в штатном
# случае — когда страница просто закрыта. Сессия при этом жива в профиле и
# поднимается сама на первом же запросе к /channel. Раньше такой случай давал
# ложную тревогу: 18.09 во время перезапусков podkop страница закрылась,
# watchdog написал владельцу, а один запрос к каналу всё восстановил. Поэтому
# перед эскалацией делаем один прогревающий запрос.
#
# Уточнение (21.09.2026): прогревать на no_active_session нельзя. tg-scraper сам
# паркует браузер после двух минут простоя (IDLE_PARK_MS в server.js), поэтому в
# покое /health/session ВСЕГДА отвечает no_active_session — и сторож каждые пять
# минут поднимал Chromium ради проверки, а парковка через две минуты гасила его
# снова. Круглосуточно: 182 прогрева за сутки и пила 12% → 40% на графике CPU.
# Теперь различаем причины: no_active_session — штатная парковка, ничего не
# делаем; unresponsive (страница есть, но висит) — вот тогда прогрев и эскалация.
set -euo pipefail

STATE_FILE="/var/lib/tg-scraper-watchdog/streak"
THRESHOLD=3
ENV_FILE="/opt/hh-gui/.env"
# Канал для прогрева: свой собственный, чтобы не тратить лимиты на источники.
WARMUP_CHANNEL="remotevibe"
# Через сколько часов без единого успешного чтения канала проверять сессию всерьёз.
# Больше периода запуска поиска (6 ч), чтобы штатная работа закрывала проверку сама.
DEEP_CHECK_HOURS=8

mkdir -p "$(dirname "$STATE_FILE")"
streak=0
[ -f "$STATE_FILE" ] && streak=$(cat "$STATE_FILE")

# Тело ответа нужно целиком: в нём причина отказа, а по ней решается, греть или нет.
session_state() { curl -s --max-time 10 http://127.0.0.1:8096/health/session || echo '{"ok":false,"reason":"unreachable"}'; }
session_ok() { curl -sf --max-time 10 http://127.0.0.1:8096/health/session > /dev/null; }

state=$(session_state)

if [[ "$state" == *'"ok":true'* ]]; then
  echo 0 > "$STATE_FILE"
  exit 0
fi

# Браузер запаркован после простоя — это штатное состояние, а не поломка: сессия
# лежит в профиле и поднимется на первом же рабочем запросе. Будить его проверкой
# незачем, иначе получаем постоянный холостой Chromium (см. шапку).
#
# Но у парковки есть цена: запаркованная страница отвечает ровно тем же, чем
# ответила бы разлогиненная, — по одному /health/session эти случаи не различить.
# Поэтому мы полагаемся на обычную работу: каждое успешное чтение канала пайплайном
# доказывает, что сессия жива, и его видно в журнале сервиса. Если доказательств
# нет уже DEEP_CHECK_HOURS часов (пайплайн ходит за источниками раз в 6 часов) —
# проверяем по-настоящему, то есть проваливаемся в прогрев ниже.
if [[ "$state" == *'no_active_session'* ]]; then
  last_ok=$(journalctl -u tg-scraper --since "-${DEEP_CHECK_HOURS} hours" --no-pager 2>/dev/null \
            | grep -cF "ok items=")
  if [ "${last_ok:-0}" -gt 0 ]; then
    echo 0 > "$STATE_FILE"
    exit 0
  fi
  logger -t tg-scraper-watchdog "no successful channel read for ${DEEP_CHECK_HOURS}h — verifying session with one warmup request"
fi

# Сюда попадаем, когда страница есть, но не отвечает (unresponsive), или сервис
# недоступен. Один запрос к каналу переоткрывает страницу из сохранённого профиля.
# Чтение канала небыстрое (Playwright + прокси), отсюда щедрый таймаут.
curl -sf --max-time 120 "http://127.0.0.1:8096/channel?username=${WARMUP_CHANNEL}&limit=1" > /dev/null || true

if session_ok; then
  # Прогрев помог: это была закрытая страница, а не потерянная сессия. Владельцу
  # не пишем, но оставляем след в журнале — если такое начнёт повторяться каждые
  # пять минут, это уже симптом, который видно по частоте этих строк.
  logger -t tg-scraper-watchdog "session recovered after warmup request (was: $state)"
  echo 0 > "$STATE_FILE"
  exit 0
fi

streak=$((streak + 1))
echo "$streak" > "$STATE_FILE"

if [ "$streak" -eq "$THRESHOLD" ]; then
  BOT_TOKEN=$(grep -oP 'TELEGRAM_BOT_TOKEN=\K.*' "$ENV_FILE")
  CHAT_ID=$(grep -oP 'TELEGRAM_CHAT_ID=\K.*' "$ENV_FILE")
  curl -s "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
    --data-urlencode "chat_id=${CHAT_ID}" \
    --data-urlencode "text=⚠️ tg-scraper: сессия Telegram Web не отвечает уже ${streak} проверок подряд (~$((streak*5)) мин), последнее состояние: ${state}. Прогревающий запрос к каналу её не поднял — похоже, аккаунт разлогинен или сервис лежит. Нужен повторный вход: POST /login/start → /login/code. Сам не перезапускаю — риск потерять профиль." \
    > /dev/null || true
fi
