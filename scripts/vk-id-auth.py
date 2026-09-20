#!/usr/bin/env python3
"""Первичная авторизация VK ID (OAuth 2.1, PKCE) для hh-gui.

VK больше не выдаёт бессрочные пользовательские токены новым приложениям, поэтому
приложение живёт на паре access (1 час) + refresh, которую само обновляет
(VkIdTokenService). Этот скрипт нужен один раз — чтобы получить первую пару
с согласия пользователя в браузере, — и повторно, если refresh-токен отозван.

Использование (на сервере, под пользователем сервиса):
  1. scripts/vk-id-auth.py url            # печатает ссылку, сохраняет code_verifier
  2. открыть ссылку в браузере, разрешить доступ; браузер уйдёт на https://localhost/?code=…
     (страница не откроется — это нормально), скопировать URL из адресной строки
  3. scripts/vk-id-auth.py exchange 'http://localhost/?code=…&device_id=…&state=…'

В настройках приложения на dev.vk.com должны быть: базовый домен «localhost»,
доверенный redirect URL «https://localhost» (http VK не принимает). Код живёт 10 минут.

Переменные: VK_ID_CLIENT_ID (или --client-id), VK_ID_TOKEN_FILE (или --token-file,
по умолчанию data/vk-id-token.json рядом с базой), VK_ID_SCOPE (по умолчанию «wall photos»).
"""
import argparse, base64, hashlib, json, os, secrets, sys, time, urllib.parse, urllib.request

AUTH_URL = "https://id.vk.ru/authorize"
TOKEN_URL = "https://id.vk.ru/oauth2/auth"
# VK принимает в «доверенный redirect URL» только https — для localhost это тоже работает
# (страница всё равно не откроется, важен только URL в адресной строке).
REDIRECT = os.environ.get("VK_ID_REDIRECT_URI", "https://localhost")


def env_file_value(key):
    """Читает ключ из /opt/hh-gui/.env или .env рядом, если переменная окружения не задана."""
    for path in (os.environ.get("HH_GUI_ENV"), "/opt/hh-gui/.env", ".env"):
        if not path or not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith(key + "="):
                    return line[len(key) + 1:].strip().strip('"').strip("'")
    return ""


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def cmd_url(a):
    verifier = b64url(secrets.token_bytes(48))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    state = secrets.token_hex(12)
    with open(a.state_file, "w") as f:
        json.dump({"code_verifier": verifier, "state": state, "client_id": a.client_id,
                   "redirect_uri": REDIRECT, "created_at": int(time.time())}, f)
    os.chmod(a.state_file, 0o600)
    q = dict(response_type="code", client_id=a.client_id, redirect_uri=REDIRECT, state=state,
             code_challenge=challenge, code_challenge_method="S256", scope=a.scope, prompt="consent")
    print(AUTH_URL + "?" + urllib.parse.urlencode(q))
    print(f"\ncode_verifier сохранён в {a.state_file}; после согласия выполни:\n"
          f"  {sys.argv[0]} exchange '<URL из адресной строки>'", file=sys.stderr)


def cmd_exchange(a):
    with open(a.state_file) as f:
        st = json.load(f)
    parsed = urllib.parse.urlparse(a.redirect_url)
    q = dict(urllib.parse.parse_qsl(parsed.query)) | dict(urllib.parse.parse_qsl(parsed.fragment))
    for k in ("code", "device_id", "state"):
        if k not in q:
            sys.exit(f"в URL нет параметра {k}: {a.redirect_url}")
    if q["state"] != st["state"]:
        sys.exit("state не совпадает — ссылка от другого запуска `url`, сгенерируй заново")
    form = dict(grant_type="authorization_code", code_verifier=st["code_verifier"], redirect_uri=st["redirect_uri"],
                code=q["code"], client_id=st["client_id"], device_id=q["device_id"], state=q["state"])
    req = urllib.request.Request(TOKEN_URL, data=urllib.parse.urlencode(form).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            resp = json.load(r)
    except urllib.error.HTTPError as e:
        resp = json.loads(e.read().decode() or "{}")
    if "error" in resp:
        sys.exit(f"VK ID отказал: {resp.get('error')} — {resp.get('error_description')}")
    tokens = {
        "access_token": resp["access_token"],
        "refresh_token": resp["refresh_token"],
        "device_id": q["device_id"],
        "state": q["state"],
        "expires_at": int(time.time()) + int(resp.get("expires_in", 3600)),
        "user_id": resp.get("user_id"),
        "scope": resp.get("scope", ""),
        "refreshed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    tmp = a.token_file + ".tmp"
    os.makedirs(os.path.dirname(os.path.abspath(a.token_file)), exist_ok=True)
    with open(tmp, "w") as f:
        json.dump(tokens, f)
    os.chmod(tmp, 0o600)
    os.replace(tmp, a.token_file)
    os.remove(a.state_file)
    print(f"ok: user_id={tokens['user_id']} scope='{tokens['scope']}' expires_in={resp.get('expires_in')}s → {a.token_file}")
    missing = [s for s in ("wall", "photos") if s not in tokens["scope"].split()]
    if missing:
        print(f"ВНИМАНИЕ: в выданных правах нет {missing} — загрузка картинок/чтение стены работать не будут; "
              f"для этих прав VK ID может требовать запрос в devsupport@corp.vk.com", file=sys.stderr)
    print("Перезапуск hh-gui не нужен: сервис подхватит файл при следующем обращении.", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--client-id", default=os.environ.get("VK_ID_CLIENT_ID") or env_file_value("VK_ID_CLIENT_ID"))
    ap.add_argument("--token-file", default=os.environ.get("VK_ID_TOKEN_FILE") or env_file_value("VK_ID_TOKEN_FILE")
                    or "/opt/hh-gui/data/vk-id-token.json")
    ap.add_argument("--state-file", default=os.path.expanduser("~/.vk-id-pkce.json"))
    ap.add_argument("--scope", default=os.environ.get("VK_ID_SCOPE", "wall photos"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("url", help="сгенерировать ссылку авторизации")
    ex = sub.add_parser("exchange", help="обменять код из redirect-URL на токены")
    ex.add_argument("redirect_url")
    a = ap.parse_args()
    if not a.client_id:
        sys.exit("нужен VK_ID_CLIENT_ID (переменная, .env или --client-id)")
    (cmd_url if a.cmd == "url" else cmd_exchange)(a)


if __name__ == "__main__":
    main()
