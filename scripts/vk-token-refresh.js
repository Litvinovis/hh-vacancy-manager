#!/usr/bin/env node
// Обновление пользовательского токена VK через сохранённую браузерную сессию.
//
// Зачем так: VK в 2026 не выдаёт бессрочные токены новым приложениям (offline →
// «invalid scope»), VK ID для Mini App недоступен, а официальные клиенты с offline
// заблокированы («application is blocked» / «Unavailable for apps with direct auth»,
// проверено 20.09.2026). Зато уже одобренному приложению залогиненный браузер получает
// токен на 24 часа БЕЗ экрана согласия — простым переходом по authorize-ссылке.
// Поэтому: один раз входим в VK headless-браузером (профиль в VK_SESSION_DIR), а этот
// скрипт по таймеру (раз в 12 часов) обновляет токен и пишет его в файл, который
// читает VkIdTokenService (формат тот же, refresh_token пустой = «обновляют снаружи»).
//
// Запуск: node vk-token-refresh.js   (systemd: scripts/vk-token-refresh.{service,timer})
// Переменные из /opt/hh-gui/.env: VK_ID_CLIENT_ID, VK_ID_TOKEN_FILE, VK_SESSION_DIR,
// TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID (алерт владельцу, если сессия протухла).
'use strict';
const fs = require('fs');
const path = require('path');
const https = require('https');

const ENV_FILE = process.env.HH_GUI_ENV || '/opt/hh-gui/.env';
const env = { ...readEnv(ENV_FILE), ...process.env };
const CLIENT_ID = env.VK_ID_CLIENT_ID;
const TOKEN_FILE = env.VK_ID_TOKEN_FILE || '/opt/hh-gui/data/vk-id-token.json';
const SESSION_DIR = env.VK_SESSION_DIR || '/opt/hh-gui/vk-session';
const SCOPE = env.VK_TOKEN_SCOPE || 'photos,wall';
const PLAYWRIGHT = env.PLAYWRIGHT_MODULE || path.join(__dirname, 'node_modules', 'playwright');

function readEnv(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)=(.*)$/);
    if (m) out[m[1]] = m[2].trim().replace(/^["']|["']$/g, '');
  }
  return out;
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(new Error('bad json: ' + b.slice(0, 200))); } });
    }).on('error', reject);
  });
}

async function alert(text) {
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_CHAT_ID) return;
  const q = new URLSearchParams({ chat_id: env.TELEGRAM_CHAT_ID, text }).toString();
  await getJson(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage?${q}`).catch(() => {});
}

function writeTokenFile(token, expiresIn, userId) {
  const now = Math.floor(Date.now() / 1000);
  const data = {
    access_token: token,
    refresh_token: '',            // пусто: обновляет этот скрипт, не VkIdTokenService
    device_id: '', state: '',
    expires_at: now + (expiresIn > 0 ? expiresIn : 86400),
    user_id: userId, scope: SCOPE.replace(/,/g, ' '),
    refreshed_at: new Date().toISOString(), source: 'browser-session',
  };
  fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true });
  const tmp = TOKEN_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data), { mode: 0o600 });
  fs.renameSync(tmp, TOKEN_FILE);
  return data;
}

(async () => {
  if (!CLIENT_ID) { console.error('нет VK_ID_CLIENT_ID в ' + ENV_FILE); process.exit(2); }
  if (!fs.existsSync(SESSION_DIR)) { console.error('нет браузерной сессии ' + SESSION_DIR); process.exit(2); }
  const { chromium } = require(PLAYWRIGHT);
  const ctx = await chromium.launchPersistentContext(SESSION_DIR, {
    headless: true, locale: 'ru-RU', viewport: { width: 1280, height: 900 },
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  });
  let exitCode = 0;
  try {
    const page = ctx.pages()[0] || await ctx.newPage();
    const url = 'https://oauth.vk.com/authorize?' + new URLSearchParams({
      client_id: CLIENT_ID, display: 'page', redirect_uri: 'https://oauth.vk.com/blank.html',
      scope: SCOPE, response_type: 'token', v: '5.199',
    }).toString();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    // Уже одобренному приложению VK сразу отдаёт redirect с токеном во фрагменте.
    // Если вместо этого открылась форма входа — сессия умерла, нужен повторный вход.
    await page.waitForURL(/blank\.html#access_token=/, { timeout: 30000 }).catch(() => {});
    const frag = new URL(page.url()).hash.replace(/^#/, '');
    const params = new URLSearchParams(frag);
    const token = params.get('access_token');
    if (!token) {
      const text = (await page.evaluate(() => document.body.innerText).catch(() => '')).replace(/\s+/g, ' ').slice(0, 300);
      const shot = path.join(path.dirname(TOKEN_FILE), 'vk-token-refresh-fail.png');
      await page.screenshot({ path: shot }).catch(() => {});
      console.error('токен не получен; url=' + page.url().replace(/access_token=[^&]+/, 'access_token=…') + ' text=' + text);
      await alert('⚠️ VK: не удалось обновить пользовательский токен — браузерная сессия, похоже, разлогинилась. '
        + 'Карточки к постам и чтение стены отвалятся, когда истечёт текущий токен. Нужен повторный вход (см. ' + shot + ').');
      exitCode = 1;
    } else {
      const who = await getJson(`https://api.vk.com/method/users.get?v=5.199&access_token=${encodeURIComponent(token)}`);
      if (!who.response) throw new Error('users.get: ' + JSON.stringify(who).slice(0, 200));
      const data = writeTokenFile(token, Number(params.get('expires_in') || 0), Number(params.get('user_id') || who.response[0].id));
      console.log(`ok: user ${who.response[0].first_name} ${who.response[0].last_name} (id ${data.user_id}), `
        + `действует до ${new Date(data.expires_at * 1000).toISOString()} → ${TOKEN_FILE}`);
    }
  } catch (e) {
    console.error('ошибка: ' + e.message);
    await alert('⚠️ VK: обновление пользовательского токена упало: ' + e.message.slice(0, 200));
    exitCode = 1;
  } finally {
    await ctx.close();
  }
  process.exit(exitCode);
})();
