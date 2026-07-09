'use strict';
/**
 * Смоук-тест фронтенда против живого бэкенда.
 *
 * Самодостаточен: логинится админом, сам создаёт тестовые данные через API
 * (3 вакансии + поиск с URL-секцией) и проверяет ключевые пользовательские
 * сценарии в реальном браузере — то, что юнит-тесты бэкенда не покрывают
 * в принципе: контракты фронт↔бэк (имена query-параметров!), рендер, URL-фильтры,
 * массовые действия, экспорт, мобильное меню.
 *
 * Запуск:  ADMIN_PASSWORD=... [BASE=http://127.0.0.1:8280] node scripts/frontend-smoke.js
 * Обвязка (сборка + запуск бэкенда на scratch-БД): scripts/run-frontend-smoke.sh
 */
const path = require('path');

function loadPlaywright() {
  try { return require('playwright'); } catch (e) { /* not installed next to script */ }
  // Локальная разработка: playwright уже стоит у scraper-сайдкара.
  return require(path.join(__dirname, '..', 'scraper', 'node_modules', 'playwright'));
}
const { chromium } = loadPlaywright();

const BASE = process.env.BASE || 'http://127.0.0.1:8280';
const PASSWORD = process.env.ADMIN_PASSWORD;
if (!PASSWORD) { console.error('ADMIN_PASSWORD is required'); process.exit(2); }

let failures = 0;
function check(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra ? ' — ' + extra : ''}`);
  if (!cond) failures++;
}

/** fetch из контекста страницы — переиспользует сессионную куку залогиненного админа. */
async function apiFetch(page, method, apiPath, body) {
  return page.evaluate(async ({ method, apiPath, body }) => {
    const res = await fetch('/api' + apiPath, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    let json = null;
    try { json = await res.json(); } catch (e) { /* пустое тело */ }
    return { status: res.status, json };
  }, { method, apiPath, body });
}

async function seed(page) {
  const search = await apiFetch(page, 'POST', '/searches', {
    name: 'Смоук поиск', area: 99, schedule: 'fullTime', salaryMin: 30000,
    queries: ['продавец'], sourceUrl: 'https://hh.ru/search/vacancy?text=smoke',
  });
  check('сид: поиск с URL-секцией создан', search.status === 200, 'http ' + search.status);

  const vacancies = [
    { hhId: 'smoke-1', person: 'Администратор', searchName: 'Смоук поиск', title: 'Продавец-консультант', company: 'ООО Ромашка',
      salaryFrom: 45000, salaryTo: 90000, currency: 'RUR', address: 'Уфа, Шакша', district: 'Шакша', url: 'https://hh.ru/vacancy/1',
      aiScore: 85, aiVerdict: 'yes', aiReason: 'Отличное совпадение', description: 'Обязанности: продажи.', status: 'new',
      scrapeStatus: 'ok', remote: false, experience: '1–3 года', employment: 'Полная занятость', keySkills: 'Продажи, Касса',
      publishedAt: '2026-07-09T09:00:00Z' },
    { hhId: 'smoke-2', person: 'Администратор', searchName: 'Смоук поиск', title: 'Оператор удалённо', company: 'ООО Дистанция',
      salaryFrom: 40000, currency: 'RUR', url: 'https://hh.ru/vacancy/2', aiScore: 70, aiVerdict: 'yes', aiReason: 'Подходит',
      description: 'Работа из дома.', status: 'new', scrapeStatus: 'ok', remote: true, publishedAt: '2026-07-09T08:00:00Z' },
    { hhId: 'smoke-3', person: 'Администратор', searchName: 'Смоук поиск', title: 'Менеджер офис', company: 'ООО Офис',
      salaryTo: 35000, currency: 'RUR', url: 'https://hh.ru/vacancy/3', aiScore: 0, aiVerdict: 'pending', description: 'Офис.',
      status: 'new', scrapeStatus: 'pending', remote: false, publishedAt: '2026-07-09T07:00:00Z' },
  ];
  for (const v of vacancies) {
    const r = await apiFetch(page, 'POST', '/vacancies', v);
    check(`сид: вакансия ${v.hhId}`, r.status === 200, 'http ' + r.status);
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  const listRequests = [];
  const externalFontRequests = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/vacancies?')) listRequests.push(r.url());
    if (/fonts\.(googleapis|gstatic)\.com/.test(r.url())) externalFontRequests.push(r.url());
  });

  // ── Логин и сид ──
  await page.goto(BASE + '/index.html');
  await page.fill('#login-username', 'admin');
  await page.fill('#login-password', PASSWORD);
  await page.click('.login-box .btn-prim');
  await page.waitForSelector('#app-root:not([style*="none"])', { timeout: 10000 });
  await seed(page);
  await page.reload();
  await page.waitForSelector('.vacancy', { timeout: 10000 });

  // ── Шрифты: локальные, без обращений к Google CDN ──
  const fontsCssStatus = await page.evaluate(async () => (await fetch('/css/fonts.css')).status);
  check('локальный /css/fonts.css отдаётся', fontsCssStatus === 200);
  check('нет запросов к fonts.googleapis/gstatic', externalFontRequests.length === 0, externalFontRequests[0] || '');

  // ── Карточки: зарплата без «КК» ──
  const salTexts = await page.locator('.v-sal').allTextContents();
  check('зарплаты без «КК»', salTexts.length > 0 && salTexts.every(t => !t.includes('КК')), JSON.stringify(salTexts));

  // ── Фильтр удалёнки: параметр remote + зеркало в URL + восстановление после reload ──
  listRequests.length = 0;
  await page.selectOption('#remote-filter', 'true');
  await page.waitForTimeout(600);
  check('фильтр «Удалёнка» шлёт remote=true', listRequests.some(u => u.includes('remote=true')));
  check('фильтр отражён в адресной строке', page.url().includes('remote=true'), page.url());
  listRequests.length = 0;
  await page.reload();
  await page.waitForSelector('.vacancy', { timeout: 10000 });
  check('после перезагрузки фильтр восстановлен из URL', await page.locator('#remote-filter').inputValue() === 'true');
  check('и запрос снова ушёл с remote=true', listRequests.some(u => u.includes('remote=true')));
  check('в списке только удалёнка (1 карточка)', await page.locator('.vacancy').count() === 1);
  await page.selectOption('#remote-filter', '');
  await page.waitForTimeout(600);

  // ── «Не оценено» = status=pending; счётчик «Новые» совпадает со списком ──
  listRequests.length = 0;
  await page.click('.nav-item[data-filter="pending"]');
  await page.waitForTimeout(600);
  check('«Не оценено» шлёт status=pending', listRequests.some(u => u.includes('status=pending')));
  check('«Не оценено»: 1 карточка', await page.locator('.vacancy').count() === 1);
  await page.click('.nav-item[data-filter="all"]');
  await page.waitForTimeout(600);
  const cntNew = parseInt(await page.locator('#cnt-new').textContent(), 10);
  await page.click('.nav-item[data-filter="new"]');
  await page.waitForTimeout(600);
  check('счётчик «Новые» = списку', cntNew === await page.locator('.vacancy').count());
  await page.click('.nav-item[data-filter="all"]');
  await page.waitForTimeout(600);

  // ── Деталка: зарплата/опыт/навыки ──
  await page.click('.v-title >> text=Продавец-консультант');
  await page.waitForSelector('#detail-panel .d-sec', { timeout: 5000 });
  const detailText = await page.locator('#detail-panel').textContent();
  check('деталка: зарплата', detailText.includes('Зарплата'));
  check('деталка: опыт и навыки', detailText.includes('1–3 года') && detailText.includes('Касса'));

  // ── Массовые действия ──
  const checks = page.locator('.v-check');
  await checks.nth(0).click();
  await checks.nth(1).click();
  check('панель bulk: «Выбрано: 2»', (await page.locator('#bulk-count').textContent()).includes('2'));
  await page.click('#bulk-bar >> text=❌ Отклонено');
  await page.waitForTimeout(800);
  check('после bulk панель скрыта', await page.locator('#bulk-bar.hidden').count() === 1);
  await page.click('.nav-item[data-filter="rejected"]');
  await page.waitForTimeout(600);
  check('bulk-отклонение применилось к 2 вакансиям', await page.locator('.vacancy').count() === 2);

  // ── Экспорт уважает активный фильтр (сейчас активен rejected) ──
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 15000 }),
    page.click('button:has-text("Экспорт")'),
  ]);
  const csv = require('fs').readFileSync(await download.path(), 'utf8');
  check('экспорт по фильтру: заголовок + 2 строки', csv.trim().split('\n').length === 3, csv.trim().split('\n').length + ' строк');
  await page.click('.nav-item[data-filter="all"]');
  await page.waitForTimeout(600);

  // ── Асинхронный пайплайн: контракт 202 + прогресс-статус ──
  const start = await apiFetch(page, 'POST', '/pipeline/analyze-pending');
  check('analyze-pending отвечает 202 сразу', start.status === 202, 'http ' + start.status);
  let st = null;
  for (let i = 0; i < 20; i++) {
    st = (await apiFetch(page, 'GET', '/pipeline/run/status')).json;
    if (st && st.running === false) break;
    await page.waitForTimeout(500);
  }
  check('фоновый запуск завершился, статус отдаёт счётчики', st && st.running === false && st.counters && !st.error,
    JSON.stringify(st));

  // ── Кабинет: «+ Добавить поиск» при карточке с URL-секцией ──
  await page.click('button:has-text("Кабинет")');
  await page.waitForSelector('#cabinet-searches-list .provider-card', { timeout: 5000 });
  check('карточка поиска содержит URL-секцию', await page.locator('#cabinet-searches-list .cab-url-discover-btn').count() > 0);
  const cardsBefore = await page.locator('#cabinet-searches-list .provider-card').count();
  await page.click('#cabinet-searches-list > button.btn-second');
  await page.waitForTimeout(300);
  check('«+ Добавить поиск» работает', await page.locator('#cabinet-searches-list .provider-card').count() === cardsBefore + 1);
  await page.click('#cabinet-modal .modal-x');

  // ── Мобильная вёрстка: off-canvas сайдбар и полноэкранная деталка ──
  await page.setViewportSize({ width: 400, height: 800 });
  await page.waitForTimeout(400);
  // Деталка осталась открытой с десктопного шага — на телефоне она становится
  // полноэкранным оверлеем, и первым делом должна быть доступна кнопка закрытия.
  check('открытая деталка на телефоне имеет кнопку ✕', await page.locator('#detail-panel .d-close').isVisible());
  await page.click('#detail-panel .d-close');
  await page.waitForTimeout(300);
  check('кнопка ✕ закрывает деталку', await page.locator('#detail-panel.hidden').count() === 1);
  check('на телефоне видна кнопка ☰', await page.locator('.sidebar-toggle').isVisible());
  await page.click('.sidebar-toggle');
  await page.waitForTimeout(400);
  check('сайдбар выехал', await page.evaluate(() => document.body.classList.contains('sidebar-open')));
  await page.click('.nav-item[data-filter="all"]');
  await page.waitForTimeout(400);
  check('выбор фильтра закрывает меню', await page.evaluate(() => !document.body.classList.contains('sidebar-open')));
  await page.click('.vacancy >> nth=0');
  await page.waitForSelector('#detail-panel .d-close', { timeout: 5000 });
  check('деталка на телефоне открывается с кнопкой закрытия', await page.locator('#detail-panel .d-close').isVisible());

  // ── Ошибки консоли (401 от /auth/me до логина — штатная проверка сессии) ──
  const realErrors = consoleErrors.filter(t => !t.includes('status of 401'));
  check('нет ошибок в консоли браузера', realErrors.length === 0, realErrors.join(' | ').slice(0, 300));

  await browser.close();
  console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('SMOKE CRASH:', e); process.exit(2); });
