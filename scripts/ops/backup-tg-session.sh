#!/bin/bash
# Копия залогиненной сессии Telegram Web (18.09.2026) и сессии VK (23.09.2026).
# Файл хранится в репозитории: scripts/ops/, ставится деплоем в /usr/local/bin.
# Профиль tg-scraper — единственное место, где живёт вход: восстановить его иначе можно
# только повторной авторизацией с кодом из Telegram владельца. Данные сессии лежат в
# LevelDB (IndexedDB/Local Storage), который нельзя честно скопировать под работающим
# браузером — поэтому сервис останавливаем штатно (SIGTERM, даём дофлашить) и запускаем снова.
# Останов короткий; пайплайн переживает его, потому что читает каналы раз в несколько часов.
set -uo pipefail

SRC=/opt/hh-gui/tg-scraper/profile-data
DEST=/mnt/storage/backups/tg-session
KEEP=4
STAMP=$(date +%Y-%m-%d)
LOG=/var/log/backup-tg-session.log

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

mountpoint -q /mnt/storage || { log "FAIL: /mnt/storage не смонтирован"; exit 1; }
[ -d "$SRC" ] || { log "FAIL: нет профиля $SRC"; exit 1; }
mkdir -p "$DEST"; chmod 700 "$DEST"

was_active=no
systemctl is-active --quiet tg-scraper && was_active=yes
if [ "$was_active" = yes ]; then
    systemctl stop tg-scraper
    # SIGTERM обработан и процесс ушёл — иначе копируем недофлашенный LevelDB
    for i in $(seq 1 30); do pgrep -f 'tg-scraper/profile-data' >/dev/null || break; sleep 1; done
fi

# Только то, что нужно для входа: сама сессия, без кэшей и слепков страниц (иначе 49 МБ → сотни).
# Часть путей может отсутствовать (Local State появляется не всегда) — перечисляем существующие,
# иначе tar вернёт 2 на несуществующем пути и прогон будет выглядеть как сбой.
paths=()
for p in "Default/IndexedDB" "Default/Local Storage" "Default/Session Storage" "Local State"; do
    [ -e "$SRC/$p" ] && paths+=("$p")
done
if [ ${#paths[@]} -eq 0 ]; then
    log "FAIL: в профиле нет ни одного файла сессии"
    [ "$was_active" = yes ] && systemctl start tg-scraper
    exit 1
fi
tar czf "$DEST/tg-session-$STAMP.tar.gz" -C "$SRC" "${paths[@]}" 2>/dev/null
rc=$?
chmod 600 "$DEST/tg-session-$STAMP.tar.gz" 2>/dev/null

[ "$was_active" = yes ] && systemctl start tg-scraper

if [ $rc -eq 0 ] && [ -s "$DEST/tg-session-$STAMP.tar.gz" ]; then
    log "ok: $(du -h "$DEST/tg-session-$STAMP.tar.gz" | cut -f1), сервис был active=$was_active"
else
    log "FAIL: архив не создан (rc=$rc)"
fi

ls -t "$DEST"/tg-session-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm --

# ── Сессия VK (профиль vk-token-refresh) ──
# Без неё пользовательский токен VK перестаёт обновляться, и посты уходят без карточек,
# пока владелец заново не войдёт в VK через scripts/vk-id-auth.py. Профиль открыт только
# на время запуска vk-token-refresh (раз в 12 ч), поэтому копируем, когда тот не идёт.
VK_SRC=/opt/hh-gui/vk-session
if [ -d "$VK_SRC" ]; then
    for i in $(seq 1 60); do systemctl is-active --quiet vk-token-refresh.service || break; sleep 2; done
    vk_paths=()
    for p in "Default/Cookies" "Default/Local Storage" "Default/IndexedDB" "Local State"; do
        [ -e "$VK_SRC/$p" ] && vk_paths+=("$p")
    done
    if [ ${#vk_paths[@]} -gt 0 ] && tar czf "$DEST/vk-session-$STAMP.tar.gz" -C "$VK_SRC" "${vk_paths[@]}" 2>/dev/null; then
        chmod 600 "$DEST/vk-session-$STAMP.tar.gz"
        log "ok: vk-session $(du -h "$DEST/vk-session-$STAMP.tar.gz" | cut -f1)"
    else
        log "FAIL: копия vk-session не создана"
    fi
    ls -t "$DEST"/vk-session-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm --
fi
