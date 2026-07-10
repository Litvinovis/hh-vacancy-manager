#!/usr/bin/env bash
# Собирает jar (если ещё не собран), поднимает бэкенд на чистой scratch-БД,
# достаёт из лога сгенерированный пароль админа и гоняет scripts/frontend-smoke.js.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${SMOKE_PORT:-8280}"
WORK_DIR="$(mktemp -d)"
APP_PID=""
# pkill по строке запуска, а не только kill $APP_PID: PID принадлежит subshell,
# и упавший прогон иначе оставляет java-зомби, который занимает порт и
# подсовывает следующему прогону чужой /index.html.
trap 'pkill -f "hh-gui-.*\.jar --server.port=$PORT" 2>/dev/null || true; rm -rf "$WORK_DIR"' EXIT

if curl -sf -o /dev/null "http://127.0.0.1:$PORT/index.html" 2>/dev/null; then
  echo "порт $PORT уже занят — уберите старый процесс или задайте SMOKE_PORT" >&2
  exit 1
fi

JAR="$(ls "$REPO_DIR"/backend/target/hh-gui-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  (cd "$REPO_DIR/backend" && mvn -B -q -DskipTests package)
  JAR="$(ls "$REPO_DIR"/backend/target/hh-gui-*.jar | head -1)"
fi

mkdir -p "$WORK_DIR/data"
(cd "$WORK_DIR" && java -jar "$JAR" --server.port="$PORT" > app.log 2>&1) &
APP_PID=$!

for i in $(seq 1 60); do
  if curl -sf -o /dev/null "http://127.0.0.1:$PORT/index.html"; then break; fi
  if [ "$i" = 60 ]; then echo "backend не поднялся"; tail -30 "$WORK_DIR/app.log"; exit 1; fi
  sleep 1
done

# Пароль появляется в логе ПОЗЖЕ готовности HTTP: сидер — CommandLineRunner,
# он выполняется после старта Tomcat. На быстром CI-раннере первый grep попадал
# в это окно, возвращал 1 и через set -e молча валил весь скрипт (голый grep в
# $(...) — это и была причина «пустых» падений джоба за 12-20 секунд).
# Непустого совпадения мало: опрос может прочитать недописанную строку лога и
# унести ОБРЕЗАННЫЙ пароль — браузерный логин тогда молча падал по таймауту
# app-root. Поэтому цикл крутится, пока пароль не подтвердится реальным логином.
ADMIN_PASSWORD=""
for i in $(seq 1 30); do
  CANDIDATE="$(grep -o 'пароль: [A-Za-z0-9]*' "$WORK_DIR/app.log" 2>/dev/null | head -1 | awk '{print $2}' || true)"
  if [ -n "$CANDIDATE" ] && curl -sf -o /dev/null -X POST -H 'Content-Type: application/json' \
       -d "{\"username\":\"admin\",\"password\":\"$CANDIDATE\"}" "http://127.0.0.1:$PORT/api/auth/login"; then
    ADMIN_PASSWORD="$CANDIDATE"
    break
  fi
  sleep 1
done
if [ -z "$ADMIN_PASSWORD" ]; then echo "рабочий пароль админа не найден в логе"; tail -30 "$WORK_DIR/app.log"; exit 1; fi

BASE="http://127.0.0.1:$PORT" ADMIN_PASSWORD="$ADMIN_PASSWORD" node "$REPO_DIR/scripts/frontend-smoke.js"
