/* HH Monitor — Frontend App */
'use strict';

const API_BASE = '/api';
let currentFilter = 'all';
let currentPage = 1;
let selectedId = null;
let currentVacancy = null;
let searchDebounce = null;

// ═══════ API ═══════
async function api(path, opts = {}) {
  const res = await fetch(API_BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  if (res.status === 401 && path !== '/auth/me' && path !== '/auth/login') {
    showLoginView();
  }
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
  return res.json();
}

// ═══════ THEME ═══════
function initTheme() {
  const saved = localStorage.getItem('hh-theme') || 'dark';
  document.documentElement.setAttribute('data-theme', saved);
  updateThemeButtons(saved);
}

function setTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem('hh-theme', theme);
  updateThemeButtons(theme);
}

function updateThemeButtons(theme) {
  document.querySelectorAll('.theme-b').forEach(b => {
    b.classList.toggle('active', b.dataset.theme === theme);
  });
}

// ═══════ TOAST ═══════
function toast(msg, type = 'ok') {
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.innerHTML = msg;
  container.appendChild(el);
  setTimeout(() => {
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 200);
  }, 2500);
}

// ═══════ UTILS ═══════
function escHtml(s) {
  if (!s) return '';
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = new Date();
  const diff = (now - d) / 1000;
  if (diff < 60) return 'только что';
  if (diff < 3600) return `${Math.floor(diff / 60)} мин назад`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}ч назад`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}д назад`;
  return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

function salaryText(v) {
  const from = v.salaryFrom;
  const to = v.salaryTo;
  if (!from && !to) return '<span class="no-sal">не указана</span>';
  if (from && to) return `${fmtN(from)}–${fmtN(to)} ₽`;
  if (from) return `от ${fmtN(from)} ₽`;
  return `до ${fmtN(to)} ₽`;
}

function fmtN(n) {
  if (n >= 1000) return (n / 1000).toFixed(0) + 'К';
  return n.toString();
}

function scoreClass(score) {
  if (score >= 60) return 's-hi';
  if (score >= 40) return 's-mi';
  if (score > 0) return 's-lo';
  return 's-ze';
}

function scoreColorVar(score, isPending) {
  if (isPending) return 'var(--muted)';
  if (score >= 60) return 'var(--good)';
  if (score >= 40) return 'var(--warn)';
  return 'var(--crit)';
}

function ringSvg(size, radius, strokeWidth, score, isPending) {
  const c = 2 * Math.PI * radius;
  const filled = isPending ? 0 : Math.max(0, Math.min(100, score)) / 100 * c;
  const cx = size / 2, cy = size / 2;
  const color = scoreColorVar(score, isPending);
  return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
    <circle cx="${cx}" cy="${cy}" r="${radius}" stroke="var(--surface3)" stroke-width="${strokeWidth}" fill="none"/>
    <circle cx="${cx}" cy="${cy}" r="${radius}" stroke="${color}" stroke-width="${strokeWidth}" fill="none" stroke-dasharray="${filled.toFixed(1)} ${c.toFixed(1)}" stroke-linecap="round"/>
  </svg>`;
}

function statusLabel(status) {
  const map = { new: 'Новые', favorite: 'Избранное', applied: 'Отклик', rejected: 'Отклонено', fraud: 'Обман', closed: 'Закрытые' };
  return map[status] || status || '—';
}

// ═══════ PEOPLE / SEARCHES ═══════
let allJobs = []; // [{person, searchName}] — the current user's own jobs (all of them if admin)

async function loadJobs() {
  try {
    allJobs = await api('/pipeline/jobs');
    const personSel = document.getElementById('person-filter');
    if (personSel) {
      const current = personSel.value;
      const people = [...new Set(allJobs.map(j => j.person))];
      personSel.innerHTML = '<option value="">Все люди</option>' +
        people.map(p => `<option value="${escHtml(p)}">${escHtml(p)}</option>`).join('');
      if (current) personSel.value = current;
    }
    updateSearchNameOptions();
  } catch (e) {
    console.error('Jobs load error:', e);
  }
}

function updateSearchNameOptions() {
  const searchSel = document.getElementById('search-name-filter');
  if (!searchSel) return;
  const person = document.getElementById('person-filter')?.value;
  const current = searchSel.value;
  const searches = [...new Set(allJobs.filter(j => !person || j.person === person).map(j => j.searchName))];
  searchSel.innerHTML = '<option value="">Все поиски</option>' +
    searches.map(s => `<option value="${escHtml(s)}">${escHtml(s)}</option>`).join('');
  if (current && searches.includes(current)) searchSel.value = current;
}

// ═══════ STATS ═══════
async function loadStats() {
  try {
    const s = await api('/stats');
    document.getElementById('cnt-all').textContent = s.total || 0;
    // Счётчик обязан совпадать с тем, что покажет список по клику: фильтр «Новые»
    // запрашивает status=new (все нетронутые), а не только неоценённые новые.
    document.getElementById('cnt-new').textContent = s.byStatus?.new || 0;
    document.getElementById('cnt-pending').textContent = s.byStatus?.pending || 0;
    document.getElementById('cnt-favorite').textContent = s.byStatus?.favorite || 0;
    document.getElementById('cnt-applied').textContent = s.byStatus?.applied || 0;
    document.getElementById('cnt-rejected').textContent = s.byStatus?.rejected || 0;
    document.getElementById('cnt-fraud').textContent = s.byStatus?.fraud || 0;
    document.getElementById('cnt-closed').textContent = s.byStatus?.closed || 0;

    // Progress
    const total = s.total || 1;
    const pending = s.byStatus?.pending || 0;
    const pct = Math.round(((total - pending) / total) * 100);
    document.getElementById('progress-pct').textContent = pct + '%';
    document.getElementById('progress-fill').style.width = pct + '%';
    document.getElementById('stat-score').textContent = s.avgScore || '—';
    document.getElementById('stat-salary').textContent = s.avgSalary ? fmtN(s.avgSalary) + ' ₽' : '—';

    // Update district filter from topDistricts
    const distSel = document.getElementById('district-filter');
    if (distSel && s.topDistricts && s.topDistricts.length) {
      const current = distSel.value;
      distSel.innerHTML = '<option value="">Все районы</option>' +
        s.topDistricts.filter(d => d.name).map(d =>
          `<option value="${escHtml(d.name)}">${escHtml(d.name)} (${d.count})</option>`
        ).join('');
      if (current) distSel.value = current;
    }

    // Update tag filter from topTags
    const tagSel = document.getElementById('tag-filter');
    if (tagSel && s.topTags && s.topTags.length) {
      const current = tagSel.value;
      tagSel.innerHTML = '<option value="">Все теги</option>' +
        s.topTags.filter(t => t.name).map(t =>
          `<option value="${escHtml(t.name)}">${escHtml(t.name)} (${t.count})</option>`
        ).join('');
      if (current) tagSel.value = current;
    }
  } catch (e) {
    console.error('Stats error:', e);
  }
}

// ═══════ VACANCY LIST ═══════
// Ответы fetch могут приходить не в порядке отправки (быстрый набор в поиске,
// клики по фильтрам) — рендерим только результат самого свежего запроса.
let loadSeq = 0;

/** Query-параметры /api/vacancies из текущего состояния фильтров (общие для списка и экспорта). */
function buildListParams() {
  const params = new URLSearchParams();

  // 'pending' и 'fraud' бэкенд понимает как фильтры по ai_verdict, остальное — по status;
  // раньше 'pending' подменялся на 'new' и «Не оценено» показывало то же, что «Новые»,
  // расходясь с собственным счётчиком в сайдбаре.
  if (currentFilter !== 'all') params.set('status', currentFilter);

  const person = document.getElementById('person-filter')?.value;
  if (person) params.set('person', person);

  const searchName = document.getElementById('search-name-filter')?.value;
  if (searchName) params.set('searchName', searchName);

  const district = document.getElementById('district-filter')?.value;
  if (district) params.set('district', district);

  const tag = document.getElementById('tag-filter')?.value;
  if (tag) params.set('tag', tag);

  const minSalary = document.getElementById('salary-filter')?.value;
  const hasSalaryOnly = document.getElementById('has-salary-filter')?.checked;
  if (minSalary) params.set('minSalary', minSalary);
  else if (hasSalaryOnly) params.set('minSalary', 1);

  const minScore = document.getElementById('score-filter')?.value;
  if (minScore) params.set('minScore', minScore);

  // Имя параметра на бэкенде — 'remote' (см. VacancyController.listVacancies);
  // с 'isRemote' фильтр «Удалёнка/Офис» молча игнорировался.
  const remote = document.getElementById('remote-filter')?.value;
  if (remote) params.set('remote', remote);

  const sort = document.getElementById('sort-filter')?.value;
  if (sort) params.set('sort', sort);

  const search = document.getElementById('search-input')?.value;
  if (search) params.set('search', search);

  return params;
}

async function loadVacancies(page = 1) {
  const seq = ++loadSeq;
  currentPage = page;
  renderChips();
  const params = buildListParams();
  params.set('page', page);
  params.set('perPage', 30);
  syncFiltersToUrl(params);

  try {
    const data = await api('/vacancies?' + params);
    if (seq !== loadSeq) return; // уже ушёл более свежий запрос
    renderList(data.vacancies || []);
    renderPagination(data);
  } catch (e) {
    console.error('Load error:', e);
  }
}

// ═══════ ФИЛЬТРЫ В URL ═══════
// Состояние фильтров зеркалится в адресную строку: работают закладки, «поделиться
// ссылкой на подборку» и восстановление после перезагрузки/повторного входа.
const URL_FILTER_INPUTS = {
  person: 'person-filter', searchName: 'search-name-filter', district: 'district-filter',
  tag: 'tag-filter', minSalary: 'salary-filter', minScore: 'score-filter',
  remote: 'remote-filter', sort: 'sort-filter', search: 'search-input',
};

function syncFiltersToUrl(listParams) {
  const params = new URLSearchParams(listParams);
  params.delete('perPage');
  if (params.get('page') === '1') params.delete('page');
  if (params.get('sort') === 'score_desc') params.delete('sort'); // дефолт не тащим в URL
  const qs = params.toString();
  history.replaceState(null, '', qs ? '?' + qs : location.pathname);
}

function restoreFiltersFromUrl() {
  const params = new URLSearchParams(location.search);
  for (const [param, id] of Object.entries(URL_FILTER_INPUTS)) {
    const val = params.get(param);
    if (!val) continue;
    const el = document.getElementById(id);
    if (!el) continue;
    // Опции селектов (люди/поиски/районы/теги) грузятся асинхронно позже —
    // подставляем временную, чтобы значение не потерялось; loadJobs/loadStats
    // при перерисовке сохраняют текущее значение.
    if (el.tagName === 'SELECT' && ![...el.options].some(o => o.value === val)) {
      const opt = document.createElement('option');
      opt.value = val;
      opt.textContent = val;
      el.appendChild(opt);
    }
    el.value = val;
  }
  if (params.get('minSalary') === '1') {
    const hasSalaryEl = document.getElementById('has-salary-filter');
    const salaryEl = document.getElementById('salary-filter');
    if (hasSalaryEl && salaryEl && salaryEl.value === '1') {
      salaryEl.value = '';
      hasSalaryEl.checked = true;
    }
  }
  const status = params.get('status');
  if (status) {
    currentFilter = status;
    document.querySelectorAll('.nav-item[data-filter]').forEach(el => {
      el.classList.toggle('active', el.dataset.filter === status);
    });
  }
  const page = parseInt(params.get('page'), 10);
  return isNaN(page) || page < 1 ? 1 : page;
}

// ═══════ ACTIVE FILTER CHIPS ═══════
function activeFilterList() {
  const list = [];
  const person = document.getElementById('person-filter')?.value;
  if (person) list.push({ key: 'person-filter', label: `👤 ${person}` });
  const searchName = document.getElementById('search-name-filter')?.value;
  if (searchName) list.push({ key: 'search-name-filter', label: `🔎 ${searchName}` });
  const district = document.getElementById('district-filter')?.value;
  if (district) list.push({ key: 'district-filter', label: `📍 ${district}` });
  const tag = document.getElementById('tag-filter')?.value;
  if (tag) list.push({ key: 'tag-filter', label: `🏷 ${tag}` });
  const salary = document.getElementById('salary-filter')?.value;
  if (salary) list.push({ key: 'salary-filter', label: `от ${fmtN(parseInt(salary, 10))} ₽` });
  const score = document.getElementById('score-filter')?.value;
  if (score) list.push({ key: 'score-filter', label: `скор ≥ ${score}` });
  const hasSalaryOnly = document.getElementById('has-salary-filter')?.checked;
  if (hasSalaryOnly && !salary) list.push({ key: 'has-salary-filter', label: 'только с ЗП' });
  const remote = document.getElementById('remote-filter')?.value;
  if (remote) list.push({ key: 'remote-filter', label: remote === 'true' ? '🌐 удалёнка' : '🏢 офис' });
  const search = document.getElementById('search-input')?.value;
  if (search) list.push({ key: 'search-input', label: `«${search}»` });
  return list;
}

function renderChips() {
  const row = document.getElementById('chips-row');
  if (!row) return;
  const filters = activeFilterList();
  if (!filters.length) { row.innerHTML = ''; return; }
  row.innerHTML = filters.map(f =>
    `<span class="fchip">${escHtml(f.label)}<button onclick="clearFilter('${f.key}')" title="Убрать">✕</button></span>`
  ).join('') + `<button class="fchip-clear" onclick="clearAllFilters()">Очистить всё</button>`;
}

function clearFilter(key) {
  const el = document.getElementById(key);
  if (!el) return;
  if (el.type === 'checkbox') el.checked = false;
  else el.value = '';
  if (key === 'person-filter') updateSearchNameOptions();
  loadVacancies(1);
}

function clearAllFilters() {
  ['person-filter', 'search-name-filter', 'district-filter', 'tag-filter',
   'salary-filter', 'score-filter', 'remote-filter', 'search-input'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = '';
  });
  const hasSalaryEl = document.getElementById('has-salary-filter');
  if (hasSalaryEl) hasSalaryEl.checked = false;
  updateSearchNameOptions();
  loadVacancies(1);
}

function renderList(vacancies) {
  const container = document.getElementById('vacancy-list');
  const empty = document.getElementById('empty-state');

  if (!vacancies.length) {
    container.innerHTML = '';
    empty.style.display = 'flex';
    return;
  }
  empty.style.display = 'none';

  container.innerHTML = vacancies.map(v => {
    const isSelected = v.id === selectedId ? 'selected' : '';
    const score = v.aiScore || 0;
    const isPending = !v.aiVerdict || v.aiVerdict === 'pending';

    let statusChip = '';
    if (v.aiVerdict === 'fraud') {
      statusChip = `<span class="chp chp-fr">🚫 Обман</span>`;
    } else if (v.status === 'favorite') {
      statusChip = `<span class="chp chp-fv">⭐ Избранное</span>`;
    } else if (v.status === 'applied') {
      statusChip = `<span class="chp chp-app">📤 Отклик</span>`;
    } else if (v.status === 'rejected') {
      statusChip = `<span class="chp chp-rej">❌ Отклонено</span>`;
    } else if (isPending) {
      statusChip = `<span class="chp chp-nw">Новое</span>`;
    }

    const tags = (v.tags || []).slice(0, 3).map(t => `<span class="chp">${escHtml(t)}</span>`).join('');
    const hasSalary = v.salaryFrom || v.salaryTo;
    const salClass = hasSalary ? '' : 'no-sal';
    // fmtN сам добавляет «К» к тысячам — дописывание ещё одной давало «90КК ₽».
    const salText = hasSalary
      ? ((v.salaryFrom && v.salaryTo) ? `${fmtN(v.salaryFrom)}–${fmtN(v.salaryTo)} ₽` : (v.salaryFrom ? `от ${fmtN(v.salaryFrom)} ₽` : `до ${fmtN(v.salaryTo)} ₽`))
      : 'не указана';

    return `
      <div class="vacancy ${isSelected}" data-id="${v.id}" onclick="openDetail(${v.id})">
        <div class="v-top">
          <input type="checkbox" class="v-check" ${bulkSelection.has(v.id) ? 'checked' : ''}
                 onclick="event.stopPropagation()" onchange="toggleCheck(${v.id}, this.checked)" title="Выбрать для массовых действий">
          <div class="v-main">
            <div class="v-title">${escHtml(v.title)}</div>
            <div class="v-co">${escHtml(v.company || '')}</div>
          </div>
          <div class="ring">${ringSvg(40, 17, 5, score, isPending)}<span class="n">${isPending ? '···' : score}</span></div>
        </div>
        <div class="v-meta">
          <span class="v-sal ${salClass}">${salText}</span>
          <span class="chp">${v.isRemote ? '🌐 Удалёнка' : ('📍 ' + (v.district || 'Офис'))}</span>
        </div>
        <div class="v-footer">
          ${statusChip}
          ${(v.person || v.searchName) ? `<span class="chp">👤 ${escHtml(v.person)} · ${escHtml(v.searchName)}</span>` : ''}
          ${tags}
        </div>
      </div>`;
  }).join('');
}

function renderPagination(data) {
  const pag = document.getElementById('pagination');
  const totalPages = Math.ceil(data.total / data.perPage);
  if (totalPages <= 1) { pag.innerHTML = ''; return; }

  let html = '';
  html += `<button class="pb" ${currentPage <= 1 ? 'disabled' : ''} onclick="loadVacancies(${currentPage - 1})">‹</button>`;

  const start = Math.max(1, currentPage - 2);
  const end = Math.min(totalPages, currentPage + 2);
  if (start > 1) html += `<button class="pb" onclick="loadVacancies(1)">1</button><span style="color:var(--muted);padding:0 4px">…</span>`;

  for (let i = start; i <= end; i++) {
    html += `<button class="pb ${i === currentPage ? 'active' : ''}" onclick="loadVacancies(${i})">${i}</button>`;
  }

  if (end < totalPages) html += `<span style="color:var(--muted);padding:0 4px">…</span><button class="pb" onclick="loadVacancies(${totalPages})">${totalPages}</button>`;
  html += `<button class="pb" ${currentPage >= totalPages ? 'disabled' : ''} onclick="loadVacancies(${currentPage + 1})">›</button>`;

  pag.innerHTML = html;
}

// ═══════ DETAIL ═══════
async function openDetail(id) {
  selectedId = id;
  document.querySelectorAll('.vacancy').forEach(el => el.classList.remove('selected'));
  const card = document.querySelector(`.vacancy[data-id="${id}"]`);
  if (card) card.classList.add('selected');

  const detail = document.getElementById('detail-panel');
  detail.classList.remove('hidden');
  detail.innerHTML = '<div class="empty"><div class="spinner"></div><div class="sub" style="margin-top:8px;">Загрузка...</div></div>';

  try {
    const v = await api('/vacancies/' + id);
    currentVacancy = v;
    renderDetail(v);
  } catch (e) {
    detail.innerHTML = `<div class="empty"><div class="text">Ошибка загрузки</div><div class="sub">${escHtml(e.message)}</div></div>`;
  }
}

function renderDetail(v) {
  const container = document.getElementById('detail-panel');
  const score = v.aiScore || 0;
  const isFraud = v.aiVerdict === 'fraud';
  const isPending = !v.aiVerdict || v.aiVerdict === 'pending';

  // Type tag
  const typeTag = isFraud
    ? '<div class="dtype fraud">⚠️ Похоже на обман</div>'
    : `<div class="dtype">${v.isRemote ? '🌐 Удалёнка' : '🏢 Офис'}</div>`;

  // Tags
  const tags = (v.tags || []).map(t => `<span class="chp" style="cursor:default">${escHtml(t)}</span>`).join('');

  // Hero: ring + headline, colored by verdict/score band
  let heroCls, heroHead, heroSub;
  if (isFraud) {
    heroCls = 'crit'; heroHead = 'Похоже на обман'; heroSub = 'Рекомендуем пропустить эту вакансию';
  } else if (isPending) {
    heroCls = 'idle'; heroHead = 'Ожидает оценки'; heroSub = 'Появится после следующего запуска анализа';
  } else if (score >= 60) {
    heroCls = 'good'; heroHead = 'Отлично подходит'; heroSub = 'Одна из лучших вакансий на сегодня';
  } else if (score >= 40) {
    heroCls = 'warn'; heroHead = 'Есть сомнения'; heroSub = 'Подходит частично — читайте обоснование ниже';
  } else {
    heroCls = 'crit'; heroHead = 'Не подходит'; heroSub = 'Не соответствует вашему профилю поиска';
  }

  const heroSection = `
    <div class="d-hero ${heroCls}">
      <div class="ring-lg">${ringSvg(62, 26, 6, score, isPending)}<span class="n">${isPending ? '···' : score}</span></div>
      <div class="txt"><div class="h">${heroHead}</div><div class="s">${heroSub}</div></div>
    </div>`;

  // Reason: prominent alarm box for fraud, plain paragraph otherwise
  let reasonSection = '';
  if (isFraud) {
    reasonSection = v.aiReason ? `
      <div class="fraud-box">
        <div class="fb-inner">
          <div class="fb-head"><span class="fb-badge">СКАМ</span><span class="fb-title">Что обнаружил AI</span></div>
          <div class="fb-reason">${escHtml(v.aiReason)}</div>
        </div>
      </div>` : '';
  } else if (!isPending && v.aiReason) {
    reasonSection = `<div class="d-reason">${escHtml(v.aiReason)}</div>`;
  }

  container.innerHTML = `
    <button class="d-close" onclick="closeDetail()" title="Закрыть">✕</button>
    <div class="db">${escHtml(v.person || 'Все вакансии')}${v.searchName ? ' · ' + escHtml(v.searchName) : ''} › <span>${escHtml(statusLabel(v.status))}</span></div>
    ${typeTag}
    <div class="dtitle">${escHtml(v.title)}</div>
    <div class="dcomp">
      ${escHtml(v.company || '')}
      ${v.url ? `· <a class="durl" href="${escHtml(v.url)}" target="_blank">hh.ru ↗</a>` : ''}
    </div>

    ${heroSection}
    ${reasonSection}

    <div class="dacts">
      <button class="act act-fav" onclick="setStatus(${v.id}, 'favorite')">⭐ Избранное</button>
      <button class="act act-app" onclick="setStatus(${v.id}, 'applied')">Отклик подан</button>
      <button class="act act-rej" onclick="setStatus(${v.id}, 'rejected')">Не подходит</button>
      <button class="act act-fra" onclick="setStatus(${v.id}, 'fraud')">🚫 Похоже на обман</button>
    </div>

    <div class="d-sec">
      <h3>Детали</h3>
      <div class="it">
        <div class="ir"><div class="it-t">Работодатель</div><div class="it-v">${escHtml(v.company || '—')}${v.trustedEmployer ? ' ✔️' : ''}</div></div>
        <div class="ir"><div class="it-t">Зарплата</div><div class="it-v">${salaryText(v)}</div></div>
        ${v.experience ? `<div class="ir"><div class="it-t">Опыт</div><div class="it-v">${escHtml(v.experience)}</div></div>` : ''}
        ${v.employment ? `<div class="ir"><div class="it-t">Занятость</div><div class="it-v">${escHtml(v.employment)}</div></div>` : ''}
        ${v.keySkills ? `<div class="ir"><div class="it-t">Навыки</div><div class="it-v">${escHtml(v.keySkills)}</div></div>` : ''}
        <div class="ir"><div class="it-t">Адрес</div><div class="it-v">${escHtml(v.address || '—')}</div></div>
        <div class="ir"><div class="it-t">Формат</div><div class="it-v">${v.isRemote ? '🌐 Удалёнка' : '🏢 Офис'}</div></div>
        <div class="ir"><div class="it-t">Опубликовано</div><div class="it-v">${formatDate(v.publishedAt)}</div></div>
        <div class="ir"><div class="it-t">Добавлено</div><div class="it-v">${formatDate(v.createdAt)}</div></div>
      </div>
    </div>

    <div class="d-sec">
      <h3>Теги и заметки</h3>
      <div class="tr">
        ${tags}
        <button class="tag-add-btn" onclick="addTag(${v.id})">+ тег</button>
      </div>
      <textarea class="ni" placeholder="Ваши заметки..." onblur="saveNotes(${v.id}, this.value)">${escHtml(v.notes || '')}</textarea>
    </div>

    <div class="d-sec">
      <h3>Описание</h3>
      <div class="ddesc">${escHtml(v.description || 'Нет описания')}</div>
    </div>

    ${(v.history && v.history.length) ? `
    <div class="d-sec">
      <h3>История</h3>
      ${v.history.map(h => `
        <div class="history-item">
          <span class="history-time">${formatDate(h.createdAt)}</span>
          <span class="history-action">${escHtml(h.action)}</span>
          ${h.details ? `<div class="history-details">${escHtml(h.details)}</div>` : ''}
        </div>
      `).join('')}
    </div>` : ''}
  `;
}

function closeDetail() {
  selectedId = null;
  currentVacancy = null;
  document.getElementById('detail-panel').classList.add('hidden');
  document.querySelectorAll('.vacancy').forEach(el => el.classList.remove('selected'));
}

// ═══════ ACTIONS ═══════
async function setStatus(id, status) {
  try {
    await api('/vacancies/' + id, { method: 'PUT', body: JSON.stringify({ status }) });
    toast(`✓ ${statusLabel(status)}`, 'ok');
    loadStats();
    loadVacancies(currentPage);
    openDetail(id);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function saveNotes(id, notes) {
  try {
    await api('/vacancies/' + id, { method: 'PUT', body: JSON.stringify({ notes }) });
  } catch (e) {
    console.error('Save notes error:', e);
  }
}

async function addTag(id) {
  const tag = prompt('Новый тег:');
  if (!tag) return;
  try {
    await api('/vacancies/' + id + '/tags', { method: 'POST', body: JSON.stringify({ tag }) });
    openDetail(id);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ МАССОВЫЕ ДЕЙСТВИЯ ═══════
// Бэкенд-эндпоинт /vacancies/bulk-status существовал всегда, но UI к нему не было —
// разбор выдачи шёл только по одной вакансии. Выделение живёт поверх страниц:
// можно отметить карточки на нескольких страницах и применить действие разом.
const bulkSelection = new Set();

function toggleCheck(id, checked) {
  if (checked) bulkSelection.add(id);
  else bulkSelection.delete(id);
  updateBulkBar();
}

function clearBulkSelection() {
  bulkSelection.clear();
  document.querySelectorAll('.v-check').forEach(cb => { cb.checked = false; });
  updateBulkBar();
}

function updateBulkBar() {
  const bar = document.getElementById('bulk-bar');
  if (!bar) return;
  bar.classList.toggle('hidden', bulkSelection.size === 0);
  const count = document.getElementById('bulk-count');
  if (count) count.textContent = `Выбрано: ${bulkSelection.size}`;
}

async function bulkSetStatus(status) {
  if (!bulkSelection.size) return;
  try {
    const r = await api('/vacancies/bulk-status', {
      method: 'POST',
      body: JSON.stringify({ ids: [...bulkSelection], status }),
    });
    toast(`✓ ${statusLabel(status)}: ${r.count} вакансий`, 'ok');
    bulkSelection.clear();
    updateBulkBar();
    loadStats();
    loadVacancies(currentPage);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ FILTERS ═══════
function setFilter(filter) {
  currentFilter = filter;
  document.querySelectorAll('.nav-item[data-filter]').forEach(el => {
    el.classList.toggle('active', el.dataset.filter === filter);
  });
  toggleSidebar(false); // на телефоне выбор фильтра закрывает выехавшее меню
  loadVacancies(1);
}

function debounceSearch() {
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => loadVacancies(1), 300);
}

// ═══════ PIPELINE ═══════
// Запуски теперь асинхронные: POST мгновенно возвращает 202, реальная работа идёт
// на бэкенде в фоне (см. PipelineJobRunner), а фронт опрашивает прогресс. Раньше
// HTTP-запрос висел все минуты работы пайплайна и умирал по таймауту браузера —
// результат терялся, а пользователь не знал, идёт ли что-то вообще.
let pipelinePollTimer = null;

const PIPELINE_TYPE_LABELS = { run: '▶ Пайплайн', analyze_pending: '⏳ Оценка необработанных', reanalyze: '🔄 Переоценка' };
const PIPELINE_COUNTER_LABELS = { collected: 'собрано', newVacancies: 'новых', analyzed: 'оценено', approved: 'одобрено', reset: 'сброшено' };

async function startPipelineJob(path, startMsg) {
  try {
    await api(path, { method: 'POST' });
    toast(startMsg);
    startPipelinePolling();
  } catch (e) {
    if (String(e.message).startsWith('409')) toast('⏳ Другая операция ещё выполняется', 'err');
    else toast('✗ ' + e.message, 'err');
  }
}

function runPipeline() {
  startPipelineJob('/pipeline/run', '▶️ Пайплайн запущен в фоне');
}

function showReanalyzeModal() {
  if (!confirm('Переоценить все вакансии? Это займёт время.')) return;
  startPipelineJob('/pipeline/reanalyze', '🔄 Переоценка запущена в фоне');
}

function analyzePending() {
  startPipelineJob('/pipeline/analyze-pending', '⏳ Оценка необработанных запущена в фоне');
}

function startPipelinePolling() {
  setPipelineButtonsDisabled(true);
  if (pipelinePollTimer) clearInterval(pipelinePollTimer);
  pipelinePollTimer = setInterval(pollPipelineStatus, 2500);
  pollPipelineStatus();
}

async function pollPipelineStatus() {
  try {
    const s = await api('/pipeline/run/status');
    if (s.running) {
      showPipelineBanner(s);
      return;
    }
    hidePipelineBanner();
    setPipelineButtonsDisabled(false);
    if (pipelinePollTimer) {
      clearInterval(pipelinePollTimer);
      pipelinePollTimer = null;
      const parts = Object.entries(s.counters || {})
        .map(([k, v]) => `${PIPELINE_COUNTER_LABELS[k] || k}: ${v}`);
      if (s.error) toast(`⚠️ Завершено с ошибкой (${escHtml(s.error)})`, 'err');
      else toast(`✓ Готово${parts.length ? ' — ' + parts.join(', ') : ''}`, 'ok');
      loadStats();
      loadVacancies(currentPage);
    }
  } catch (e) { /* сеть мигнула — следующий тик опроса разберётся */ }
}

/** Подхватить уже идущий фоновый запуск (перезагрузка страницы, второй логин). */
async function resumePipelinePollingIfRunning() {
  try {
    const s = await api('/pipeline/run/status');
    if (s.running) startPipelinePolling();
  } catch (e) { /* ignore */ }
}

function showPipelineBanner(s) {
  let banner = document.getElementById('pipeline-banner');
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'pipeline-banner';
    banner.className = 'pipeline-banner';
    const topbar = document.querySelector('.topbar');
    if (topbar) topbar.prepend(banner);
  }
  const label = PIPELINE_TYPE_LABELS[s.type] || 'Выполняется';
  banner.innerHTML = `<span class="spinner"></span> ${label}: ${s.jobsDone}/${s.jobsTotal}` +
    (s.currentJob ? ` · ${escHtml(s.currentJob)}` : '');
  banner.style.display = 'flex';
}

function hidePipelineBanner() {
  const banner = document.getElementById('pipeline-banner');
  if (banner) banner.style.display = 'none';
}

function setPipelineButtonsDisabled(disabled) {
  ['btn-run-pipeline', 'btn-analyze-pending', 'btn-reanalyze'].forEach(id => {
    const b = document.getElementById(id);
    if (b) b.disabled = disabled;
  });
}

/**
 * Раньше открывалось /api/vacancies?perPage=99999 — бэкенд молча ограничивает
 * perPage до 200 (см. VacancyController), так что «экспорт» отдавал только
 * первые 200 строк и в виде сырого JSON. Теперь выкачиваем все страницы и
 * отдаём CSV-файл, который открывается в Excel/Numbers.
 */
async function exportData() {
  toast('⇩ Экспорт: собираю данные...');
  const cols = ['id', 'hhId', 'title', 'company', 'person', 'searchName', 'salaryFrom', 'salaryTo',
    'district', 'address', 'isRemote', 'aiScore', 'aiVerdict', 'aiReason', 'status', 'url', 'publishedAt', 'createdAt'];
  const csvCell = (val) => {
    const s = val == null ? '' : String(val);
    return /[",;\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  try {
    const rows = [];
    let page = 1;
    let total = Infinity;
    // Экспортируется текущая подборка: все активные фильтры (человек, статус,
    // зарплата, поиск…) применяются и к выгрузке, а не вся база без разбора.
    while (rows.length < total && page <= 100) {
      const params = buildListParams();
      params.set('perPage', 200);
      params.set('page', page);
      const data = await api('/vacancies?' + params);
      total = data.total || 0;
      const batch = data.vacancies || [];
      if (!batch.length) break;
      rows.push(...batch);
      page++;
    }
    const lines = [cols.join(';')].concat(
      rows.map(v => cols.map(c => csvCell(v[c])).join(';'))
    );
    // BOM — чтобы Excel открыл UTF-8 кириллицу без кракозябр.
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `vacancies-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
    toast(`✓ Экспортировано ${rows.length} вакансий`, 'ok');
  } catch (e) {
    toast('✗ Экспорт не удался: ' + e.message, 'err');
  }
}

async function toggleNotifications() {
  try {
    const s = await api('/settings/notifications');
    const newState = !s.enabled;
    await api('/settings/notifications', { method: 'POST', body: JSON.stringify({ enabled: newState }) });
    applyNotifyButtonState(newState);
    toast(`${newState ? '🔔' : '🔕'} Уведомления ${newState ? 'включены' : 'отключены'}`);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// Иконка колокольчика раньше никогда не отражала реальное состояние — всегда 🔔,
// включены уведомления или нет; узнать текущее состояние можно было только методом тыка.
function applyNotifyButtonState(enabled) {
  const btn = document.getElementById('btn-notify-toggle');
  if (!btn) return;
  btn.textContent = enabled ? '🔔' : '🔕';
  btn.title = enabled ? 'Уведомления включены' : 'Уведомления отключены';
}

async function syncNotifyButton() {
  try {
    const s = await api('/settings/notifications');
    applyNotifyButtonState(!!s.enabled);
  } catch (e) { /* ignore */ }
}

// ═══════ SETTINGS (admin-only, session-authenticated — see AuthController) ═══════
let settingsDescriptors = [];

async function showSettingsModal() {
  let modal = document.getElementById('settings-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'settings-modal';
    modal.className = 'modal-overlay';
    document.body.appendChild(modal);
  }

  try {
    const data = await api('/settings');
    settingsDescriptors = data.descriptors || [];
    const provData = await api('/settings/providers');
    renderSettingsForm(data.values || {}, provData.providers || []);
  } catch (e) {
    toast('✗ Ошибка загрузки настроек: ' + e.message, 'err');
  }
}

function renderSettingsForm(values, providers) {
  const modal = document.getElementById('settings-modal');

  // Build schedule options for pipelineIntervalMs
  const intervalOpts = [];
  for (let m = 10; m <= 60; m += 10) intervalOpts.push({ v: m * 60000, l: `Каждые ${m} мин` });
  for (let h = 1; h <= 24; h++) intervalOpts.push({ v: h * 3600000, l: h === 1 ? 'Каждый 1 час' : `Каждые ${h} часа` });

  // Build schedule options for dailyCron
  const dailyCronOpts = [];
  for (let h = 0; h <= 23; h++) {
    const hh = String(h).padStart(2, '0');
    dailyCronOpts.push({ v: `0 0 ${hh} * * *`, l: `Ежедневно в ${hh}:00` });
    dailyCronOpts.push({ v: `0 30 ${hh} * * *`, l: `Ежедневно в ${hh}:30` });
  }

  let html = '';
  for (const d of settingsDescriptors) {
    const val = values[d.key] ?? d.currentValue;
    const tooltip = d.description ? `<span class="settings-help" title="${escHtml(d.description)}">❓</span>` : '';

    if (d.type === 'boolean') {
      html += `
        <div class="settings-row">
          <div class="settings-label">${escHtml(d.label)} ${tooltip}</div>
          <label class="switch">
            <input type="checkbox" data-key="${d.key}" ${val ? 'checked' : ''}>
            <span class="slider"></span>
          </label>
        </div>`;
    } else if (d.key === 'pipelineIntervalMs') {
      const selVal = intervalOpts.find(o => o.v === val) ? val : '';
      html += `
        <div class="settings-row">
          <div class="settings-label">${escHtml(d.label)} ${tooltip}</div>
          <select class="settings-sel" data-key="${d.key}">
            ${intervalOpts.map(o => `<option value="${o.v}" ${o.v == selVal ? 'selected' : ''}>${o.l}</option>`).join('')}
            <option value="" ${!selVal ? 'selected' : ''}>Свое значение (мс)</option>
          </select>
          <input type="number" class="settings-num-mini" data-key="${d.key}_custom" placeholder="мс" min="${d.min||1}" max="${d.max||86400000}" style="display:${selVal ? 'none' : 'inline-block'}">
        </div>`;
    } else if (d.key === 'dailyCron') {
      const selVal = dailyCronOpts.find(o => o.v === val) ? val : '';
      html += `
        <div class="settings-row">
          <div class="settings-label">${escHtml(d.label)} ${tooltip}</div>
          <select class="settings-sel" data-key="${d.key}">
            ${dailyCronOpts.map(o => `<option value="${o.v}" ${o.v === selVal ? 'selected' : ''}>${o.l}</option>`).join('')}
            <option value="" ${!selVal ? 'selected' : ''}>Свой cron</option>
          </select>
          <input type="text" class="settings-num-mini" data-key="${d.key}_custom" placeholder="0 0 12 * * *" value="${!selVal ? escHtml(val) : ''}" style="display:${selVal ? 'none' : 'inline-block'}">
        </div>`;
    } else {
      html += `
        <div class="settings-row">
          <div class="settings-label">${escHtml(d.label)} ${tooltip}</div>
          <input type="number" class="settings-num" data-key="${d.key}" value="${val}" min="${d.min??0}" max="${d.max??999999}">
        </div>`;
    }
  }

  modal.innerHTML = `
    <div class="modal-box modal-box-wide">
      <div class="modal-head"><h3>⚙️ Настройки</h3><button class="modal-x" onclick="closeSettingsModal()">✕</button></div>
      <div class="modal-body settings-body">${html}
        <div class="settings-separator"><span>🤖 AI Провайдеры</span></div>
        <div class="providers-list" id="providers-list">
          ${renderProvidersList(providers)}
        </div>
      </div>
      <div class="modal-foot">
        <button class="btn btn-ghost" onclick="closeSettingsModal()">Отмена</button>
        <button class="btn btn-prim" onclick="saveSettings()">💾 Сохранить</button>
      </div>
    </div>`;
  modal.style.display = 'flex';

  // Toggle custom input visibility for selects
  modal.querySelectorAll('.settings-sel').forEach(sel => {
    sel.addEventListener('change', () => {
      const key = sel.dataset.key;
      const custom = modal.querySelector(`[data-key="${key}_custom"]`);
      if (custom) custom.style.display = sel.value === '' ? 'inline-block' : 'none';
    });
  });
}

function closeSettingsModal() {
  const modal = document.getElementById('settings-modal');
  if (modal) modal.style.display = 'none';
}

async function saveSettings() {
  const modal = document.getElementById('settings-modal');
  const updates = {};

  // Collect values
  const processed = new Set();
  modal.querySelectorAll('[data-key]').forEach(el => {
    const key = el.dataset.key;
    if (key.endsWith('_custom') || processed.has(key)) return;
    processed.add(key);

    if (el.type === 'checkbox') {
      updates[key] = el.checked;
    } else if (el.tagName === 'SELECT') {
      if (el.value === '') {
        // Custom value; пустое/нечисловое поле не отправляем — parseInt('') это NaN,
        // JSON.stringify превращал его в null и бэкенд отвечал «Неверный тип».
        const custom = modal.querySelector(`[data-key="${key}_custom"]`);
        if (custom) {
          const cv = custom.type === 'number' ? parseInt(custom.value) : custom.value.trim();
          if (custom.type === 'number' ? !isNaN(cv) : cv) updates[key] = cv;
        }
      } else if (key === 'pipelineIntervalMs') {
        updates[key] = parseInt(el.value);
      } else {
        updates[key] = el.value;
      }
    } else if (el.type === 'number') {
      const v = parseInt(el.value);
      if (isNaN(v)) return;
      // Validate min/max
      const desc = settingsDescriptors.find(d => d.key === key);
      if (desc && desc.min !== null && v < desc.min) { toast(`✗ ${desc.label}: минимум ${desc.min}`, 'err'); return; }
      if (desc && desc.max !== null && v > desc.max) { toast(`✗ ${desc.label}: максимум ${desc.max}`, 'err'); return; }
      updates[key] = v;
    }
  });

  try {
    const r = await api('/settings', { method: 'POST', body: JSON.stringify(updates) });

    // Save providers
    const list = document.getElementById('providers-list');
    if (list) {
      const cards = list.querySelectorAll('.provider-card');
      const providers = Array.from(cards).map(card => {
        const inputs = card.querySelectorAll('.provider-inp');
        const data = {};
        inputs.forEach(inp => { data[inp.dataset.field] = inp.value; });
        return data;
      });
      await api('/settings/providers', { method: 'PUT', body: JSON.stringify(providers) });
    }

    if (r.errors && Object.keys(r.errors).length) {
      const msgs = Object.values(r.errors).join('; ');
      toast('✗ ' + msgs, 'err');
    } else {
      toast('✓ Настройки сохранены', 'ok');
      closeSettingsModal();
    }
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ AI PROVIDERS EDITOR ═══════

function renderProvidersList(providers) {
  if (!providers || providers.length === 0) {
    return `<div class="providers-empty">Нет провайдеров. Добавьте хотя бы один.</div>
      <button class="btn btn-second" onclick="addProvider()" style="margin-top:8px;width:100%">+ Добавить провайдера</button>`;
  }
  return providers.map((p) => `
    <div class="provider-card">
      <div class="provider-header">
        <span class="provider-num"></span>
        <span class="provider-name-badge">${escHtml(p.name || '—')}</span>
        <div class="provider-arrows">
          <button class="provider-btn" onclick="moveProvider(this, -1)" title="Вверх">↑</button>
          <button class="provider-btn" onclick="moveProvider(this, 1)" title="Вниз">↓</button>
        </div>
        <button class="provider-btn provider-del" onclick="removeProvider(this)" title="Удалить">✕</button>
      </div>
      <div class="provider-body">
        <div class="provider-field">
          <label>Название</label>
          <input class="provider-inp" data-field="name" value="${escHtml(p.name)}" placeholder="GitHub Models">
        </div>
        <div class="provider-field">
          <label>URL API</label>
          <input class="provider-inp" data-field="url" value="${escHtml(p.url)}" placeholder="https://models.inference.ai.azure.com/chat/completions">
        </div>
        <div class="provider-field">
          <label>API Key</label>
          <input class="provider-inp provider-key" type="password" data-field="apiKey" value="${escHtml(p.apiKeyFull || '')}" placeholder="sk-...">
        </div>
        <div class="provider-field">
          <label>Модель</label>
          <input class="provider-inp" data-field="model" value="${escHtml(p.model)}" placeholder="gpt-4o-mini">
        </div>
        <div class="provider-field">
          <label>Пауза между запросами, мс (пусто = глобальная)</label>
          <input class="provider-inp" data-field="requestDelayMs" value="${p.requestDelayMs != null ? p.requestDelayMs : ''}" placeholder="12000 для free-tier, 1000 для платных">
        </div>
      </div>
    </div>
  `).join('') + `
    <button class="btn btn-second" onclick="addProvider()" style="margin-top:8px;width:100%">+ Добавить провайдера</button>`;
}

function addProvider() {
  const list = document.getElementById('providers-list');
  if (!list) return;
  const addBtn = list.querySelector('.btn-second');
  const card = document.createElement('div');
  card.className = 'provider-card';
  card.innerHTML = `
    <div class="provider-header">
      <span class="provider-num"></span>
      <span class="provider-name-badge">Новый</span>
      <div class="provider-arrows">
        <button class="provider-btn" onclick="moveProvider(this, -1)" title="Вверх">↑</button>
        <button class="provider-btn" onclick="moveProvider(this, 1)" title="Вниз">↓</button>
      </div>
      <button class="provider-btn provider-del" onclick="removeProvider(this)" title="Удалить">✕</button>
    </div>
    <div class="provider-body">
      <div class="provider-field">
        <label>Название</label>
        <input class="provider-inp" data-field="name" placeholder="GitHub Models" value="">
      </div>
      <div class="provider-field">
        <label>URL API</label>
        <input class="provider-inp" data-field="url" placeholder="https://..." value="">
      </div>
      <div class="provider-field">
        <label>API Key</label>
        <input class="provider-inp provider-key" type="password" data-field="apiKey" placeholder="sk-..." value="">
      </div>
      <div class="provider-field">
        <label>Модель</label>
        <input class="provider-inp" data-field="model" placeholder="gpt-4o-mini" value="">
      </div>
      <div class="provider-field">
        <label>Пауза между запросами, мс (пусто = глобальная)</label>
        <input class="provider-inp" data-field="requestDelayMs" placeholder="12000 для free-tier, 1000 для платных" value="">
      </div>
    </div>`;
  if (addBtn) list.insertBefore(card, addBtn);
  else list.appendChild(card);
  updateProviderNumbers();
}

function removeProvider(btn) {
  const card = btn.closest('.provider-card');
  if (card) card.remove();
  updateProviderNumbers();
}

function moveProvider(btn, direction) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const list = document.getElementById('providers-list');
  const cards = Array.from(list.querySelectorAll('.provider-card'));
  const idx = cards.indexOf(card);
  const target = idx + direction;
  if (target < 0 || target >= cards.length) return;
  if (direction < 0) list.insertBefore(card, cards[target]);
  else list.insertBefore(cards[target], card);
  updateProviderNumbers();
}

function updateProviderNumbers() {
  const list = document.getElementById('providers-list');
  if (!list) return;
  list.querySelectorAll('.provider-card').forEach((card, i) => {
    const num = card.querySelector('.provider-num');
    if (num) num.textContent = '#' + (i + 1);
    const upBtn = card.querySelector('[title="Вверх"]');
    if (upBtn) upBtn.disabled = i === 0;
  });
}

// ═══════ PERSONAL CABINET ═══════
const MAX_SEARCHES = 3;

async function showCabinetModal() {
  let modal = document.getElementById('cabinet-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'cabinet-modal';
    modal.className = 'modal-overlay';
    document.body.appendChild(modal);
  }
  try {
    const searches = await api('/searches');
    renderCabinetModal(searches);
  } catch (e) {
    toast('✗ Ошибка загрузки кабинета: ' + e.message, 'err');
  }
}

function closeCabinetModal() {
  const modal = document.getElementById('cabinet-modal');
  if (modal) modal.style.display = 'none';
}

function renderCabinetModal(searches) {
  const modal = document.getElementById('cabinet-modal');
  const u = currentUser || {};

  modal.innerHTML = `
    <div class="modal-box modal-box-wide">
      <div class="modal-head"><h3>👤 Личный кабинет — ${escHtml(u.displayName || '')}</h3><button class="modal-x" onclick="closeCabinetModal()">✕</button></div>
      <div class="modal-body settings-body">
        <div class="cabinet-section-title">Профиль</div>
        <div class="provider-body" style="grid-template-columns:1fr 1fr;padding:0 0 14px">
          <div class="provider-field">
            <label>Город</label>
            <input class="provider-inp" id="cab-city" value="${escHtml(u.city || '')}" placeholder="Уфа">
          </div>
          <div class="provider-field provider-field-full">
            <label>Опыт и бэкграунд (для ИИ-анализа)</label>
            <textarea class="provider-textarea" id="cab-experience" placeholder="Например: 5 лет в рознице, опыт работы с кассой и клиентами">${escHtml(u.experienceSummary || '')}</textarea>
          </div>
        </div>
        <button class="btn btn-second" onclick="saveCabinetProfile()" style="width:100%;margin-bottom:18px">💾 Сохранить профиль</button>

        <div class="cabinet-section-title">Смена пароля</div>
        <div class="provider-body" style="grid-template-columns:1fr 1fr;padding:0 0 14px">
          <div class="provider-field">
            <label>Текущий пароль</label>
            <input class="provider-inp" type="password" id="cab-old-password">
          </div>
          <div class="provider-field">
            <label>Новый пароль</label>
            <input class="provider-inp" type="password" id="cab-new-password">
          </div>
        </div>
        <button class="btn btn-second" onclick="changeCabinetPassword()" style="width:100%;margin-bottom:18px">🔑 Сменить пароль</button>

        <div class="settings-separator"><span>🔍 Мои поиски (${searches.length}/${MAX_SEARCHES})</span></div>
        <div class="providers-list" id="cabinet-searches-list">
          ${renderCabinetSearches(searches)}
        </div>
      </div>
      <div class="modal-foot">
        <button class="btn btn-ghost" onclick="closeCabinetModal()">Закрыть</button>
      </div>
    </div>`;
  modal.style.display = 'flex';
}

function renderCabinetSearches(searches) {
  const cards = searches.map(s => cabinetSearchCardHtml(s)).join('');
  const addBtn = searches.length < MAX_SEARCHES
    ? `<button class="btn btn-second" onclick="addCabinetSearchCard()" style="margin-top:8px;width:100%">+ Добавить поиск</button>`
    : `<div class="providers-empty">Достигнут лимит: ${MAX_SEARCHES} поиска на пользователя</div>`;
  return cards + addBtn;
}

function cabinetSearchCardHtml(s) {
  return searchCardHtml(s, { global: false });
}

/**
 * Shared card markup for both a personal search (cabinet, one user) and a global
 * search (admin, everyone) — the fields are the same, only save/delete wiring and
 * the "isGlobal" flag sent to the backend differ. See globalSearchCardHtml.
 */
function searchCardHtml(s, opts) {
  const id = s.id != null ? s.id : '';
  const global = !!opts.global;
  // Поиск по ссылке — админская настройка: обычному пользователю блок не показываем
  // (бэкенд от не-админа sourceUrl/runIntervalHours всё равно не примет).
  const isAdmin = currentUser?.role === 'admin';
  // escHtml обязателен: значения пользовательские, и «</textarea>» или тег в
  // слове-исключении без экранирования ломал разметку всей карточки (инъекция HTML).
  const listVal = (arr) => escHtml((arr || []).join('\n'));
  const delFn = global ? 'deleteGlobalSearch' : 'deleteCabinetSearch';
  const saveFn = global ? 'saveGlobalSearch' : 'saveCabinetSearch';
  return `
    <div class="provider-card" data-search-id="${id}" data-global="${global ? '1' : '0'}">
      <div class="provider-header">
        <span class="provider-name-badge">${escHtml(s.name || 'Новый поиск')}${global ? ' 🌐' : ''}</span>
        <label class="switch" style="width:36px;height:20px" title="Активен">
          <input type="checkbox" class="cab-enabled" ${s.enabled !== false ? 'checked' : ''}>
          <span class="slider"></span>
        </label>
        <button class="provider-btn provider-del" onclick="${delFn}(this)" title="Удалить">✕</button>
      </div>
      <div class="provider-body">
        <div class="provider-field">
          <label>Название</label>
          <input class="provider-inp cab-name" value="${escHtml(s.name || '')}" placeholder="${global ? 'Интересная удалёнка' : 'Рядом с домом'}">
        </div>
        <div class="provider-field">
          <label>Код региона hh.ru (99 = Уфа, 113 = вся Россия)</label>
          <input class="provider-inp cab-area" type="number" value="${s.area || 113}">
        </div>
        <div class="provider-field">
          <label>График</label>
          <select class="provider-inp cab-schedule">
            <option value="fullTime" ${s.schedule === 'fullTime' ? 'selected' : ''}>Полный день</option>
            <option value="remote" ${s.schedule === 'remote' ? 'selected' : ''}>Удалённо</option>
            <option value="shift" ${s.schedule === 'shift' ? 'selected' : ''}>Сменный график</option>
            <option value="flexible" ${s.schedule === 'flexible' ? 'selected' : ''}>Гибкий график</option>
          </select>
        </div>
        <div class="provider-field">
          <label>Мин. зарплата, ₽</label>
          <input class="provider-inp cab-salary-min" type="number" value="${s.salaryMin || 0}">
        </div>
        <div class="provider-field provider-field-full">
          <label>Поисковые запросы (по одному на строку${isAdmin ? ', необязательно при поиске по ссылке ниже' : ''})</label>
          <textarea class="provider-textarea cab-queries" placeholder="продавец&#10;консультант">${listVal(s.queries)}</textarea>
        </div>
        <div class="provider-field">
          <label>Приоритетные районы</label>
          <textarea class="provider-textarea cab-districts">${listVal(s.priorityDistricts)}</textarea>
        </div>
        <div class="provider-field">
          <label>Желаемые навыки</label>
          <textarea class="provider-textarea cab-skills">${listVal(s.skills)}</textarea>
        </div>
        <div class="provider-field">
          <label>Не подходит</label>
          <textarea class="provider-textarea cab-not-suitable">${listVal(s.notSuitable)}</textarea>
        </div>
        <div class="provider-field">
          <label>Слова-исключения из названия</label>
          <textarea class="provider-textarea cab-exclude">${listVal(s.excludeWords)}</textarea>
        </div>
        <div class="provider-field provider-field-full">
          <label>Заметка для ИИ-оценки</label>
          <textarea class="provider-textarea cab-ai-notes" placeholder="Например: близость к дому важнее интересности задач">${escHtml(s.aiNotes || '')}</textarea>
        </div>
      </div>
      ${isAdmin ? urlDiscoverySectionHtml(s, id) : ''}
      <div class="modal-foot" style="padding:10px 12px">
        <button class="btn btn-prim" onclick="${saveFn}(this)" style="width:100%">💾 Сохранить поиск</button>
      </div>
    </div>`;
}

function urlDiscoverySectionHtml(s, id) {
  // «Найти и оценить сейчас» требует сохранённый поиск (searchId) — на новой
  // карточке показываем только поле ссылки и интервал, чтобы админ мог создать
  // URL-only поиск одним сохранением.
  const runNow = id ? `
      <div class="provider-field">
        <label>Страниц за ручной запуск (≈50 вак./стр.)</label>
        <input class="provider-inp cab-url-discover-pages" type="number" min="1" max="10" value="3">
      </div>
      <button class="btn btn-second cab-url-discover-btn" onclick="runUrlDiscovery(this)" style="width:100%;grid-column:1/-1">🔎 Найти и оценить сейчас</button>` : '';
  return `
    <div class="provider-body" style="grid-template-columns:1fr 1fr;border-top:1px solid var(--border);padding-top:12px">
      <div class="provider-field provider-field-full">
        <label>🔗 Поиск по ссылке — вставьте ссылку на результаты поиска hh.ru, собранную с фильтрами в браузере; найденные вакансии оценятся по критериям этого поиска</label>
        <input class="provider-inp cab-source-url" value="${escHtml(s.sourceUrl || '')}" placeholder="https://hh.ru/search/vacancy?text=...&amp;area=99&amp;...">
      </div>
      <div class="provider-field">
        <label>Автозапуск раз в, часов (пусто = только вручную)</label>
        <input class="provider-inp cab-run-interval" type="number" min="1" value="${s.runIntervalHours != null ? s.runIntervalHours : ''}">
      </div>${runNow}
    </div>`;
}

async function runUrlDiscovery(btn) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const searchId = card.dataset.searchId;
  const url = card.querySelector('.cab-source-url')?.value?.trim();
  const maxPages = parseInt(card.querySelector('.cab-url-discover-pages')?.value) || 3;
  if (!url) { toast('✗ Вставьте ссылку на поиск hh.ru', 'err'); return; }

  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = '⏳ Ищу и оцениваю...';
  try {
    const result = await api('/pipeline/discover-from-url', {
      method: 'POST',
      body: JSON.stringify({ searchId: parseInt(searchId), url, maxPages }),
    });
    toast(`✓ Найдено: ${result.newVacancies}, оценено: ${result.analyzed}, одобрено: ${result.approved}`, 'ok');
    loadVacancies();
    loadStats();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

function addCabinetSearchCard() {
  const list = document.getElementById('cabinet-searches-list');
  if (!list) return;
  // Только прямые дети списка: внутри сохранённой карточки поиска есть собственная
  // .btn-second («Найти и оценить сейчас»), и querySelector без :scope находил её
  // первой — insertBefore с не-ребёнком списка бросал DOMException, и кнопка
  // «+ Добавить поиск» ломалась, как только появлялся первый сохранённый поиск.
  const addBtn = list.querySelector(':scope > .btn-second, :scope > .providers-empty');
  const wrap = document.createElement('div');
  wrap.innerHTML = cabinetSearchCardHtml({});
  const card = wrap.firstElementChild;
  if (addBtn) list.insertBefore(card, addBtn); else list.appendChild(card);
  if (addBtn && addBtn.classList.contains('btn-second')) addBtn.remove(); // will be re-added on next full render if still under limit
}

function readCabinetSearchCard(card) {
  const splitLines = (el) => (el.value || '').split('\n').map(s => s.trim()).filter(Boolean);
  const runIntervalRaw = card.querySelector('.cab-run-interval')?.value?.trim();
  return {
    name: card.querySelector('.cab-name')?.value?.trim() || '',
    area: parseInt(card.querySelector('.cab-area')?.value) || 113,
    schedule: card.querySelector('.cab-schedule')?.value || '',
    salaryMin: parseInt(card.querySelector('.cab-salary-min')?.value) || 0,
    queries: splitLines(card.querySelector('.cab-queries')),
    priorityDistricts: splitLines(card.querySelector('.cab-districts')),
    skills: splitLines(card.querySelector('.cab-skills')),
    notSuitable: splitLines(card.querySelector('.cab-not-suitable')),
    excludeWords: splitLines(card.querySelector('.cab-exclude')),
    aiNotes: card.querySelector('.cab-ai-notes')?.value?.trim() || '',
    enabled: card.querySelector('.cab-enabled')?.checked !== false,
    sourceUrl: card.querySelector('.cab-source-url')?.value?.trim() || '',
    runIntervalHours: runIntervalRaw ? parseInt(runIntervalRaw) : null,
  };
}

async function saveCabinetSearch(btn) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const id = card.dataset.searchId;
  const payload = readCabinetSearchCard(card);
  if (!payload.name) { toast('✗ Укажите название поиска', 'err'); return; }
  if (!payload.queries.length && !payload.sourceUrl) {
    toast('✗ Укажите хотя бы один поисковый запрос', 'err');
    return;
  }
  try {
    if (id) {
      await api(`/searches/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    } else {
      await api('/searches', { method: 'POST', body: JSON.stringify(payload) });
    }
    toast('✓ Поиск сохранён', 'ok');
    showCabinetModal(); // reload with fresh state (ids, limit)
    loadJobs();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function deleteCabinetSearch(btn) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const id = card.dataset.searchId;
  if (!id) { card.remove(); return; } // never-saved new card, just drop it locally
  if (!confirm('Удалить этот поиск?')) return;
  try {
    await api(`/searches/${id}`, { method: 'DELETE' });
    toast('✓ Поиск удалён', 'ok');
    showCabinetModal();
    loadJobs();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ ОБЩИЕ ПОИСКИ (админ, видны всем пользователям) ═══════

function globalSearchCardHtml(s) {
  return searchCardHtml(s, { global: true });
}

function addGlobalSearchCard() {
  const list = document.getElementById('admin-global-searches-list');
  if (!list) return;
  const wrap = document.createElement('div');
  wrap.innerHTML = globalSearchCardHtml({});
  // Новая карточка — перед кнопкой «+ Добавить», а не после неё (appendChild
  // визуально ронял карточку под кнопку). :scope — см. addCabinetSearchCard.
  const addBtn = list.querySelector(':scope > .btn-second');
  if (addBtn) list.insertBefore(wrap.firstElementChild, addBtn);
  else list.appendChild(wrap.firstElementChild);
}

async function saveGlobalSearch(btn) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const id = card.dataset.searchId;
  const payload = { ...readCabinetSearchCard(card), isGlobal: true };
  if (!payload.name) { toast('✗ Укажите название поиска', 'err'); return; }
  if (!payload.queries.length && !payload.sourceUrl) {
    toast('✗ Укажите поисковые запросы или ссылку на поиск hh.ru', 'err');
    return;
  }
  try {
    if (id) {
      await api(`/searches/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    } else {
      await api('/searches', { method: 'POST', body: JSON.stringify(payload) });
    }
    toast('✓ Общий поиск сохранён', 'ok');
    showAdminModal();
    loadJobs();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function deleteGlobalSearch(btn) {
  const card = btn.closest('.provider-card');
  if (!card) return;
  const id = card.dataset.searchId;
  if (!id) { card.remove(); return; }
  if (!confirm('Удалить этот общий поиск? Вакансии, которые он уже нашёл, останутся в базе.')) return;
  try {
    await api(`/searches/${id}`, { method: 'DELETE' });
    toast('✓ Общий поиск удалён', 'ok');
    showAdminModal();
    loadJobs();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function saveCabinetProfile() {
  const city = document.getElementById('cab-city')?.value?.trim() || '';
  const experienceSummary = document.getElementById('cab-experience')?.value?.trim() || '';
  try {
    await api('/auth/me', { method: 'PUT', body: JSON.stringify({ city, experienceSummary }) });
    if (currentUser) { currentUser.city = city; currentUser.experienceSummary = experienceSummary; }
    toast('✓ Профиль сохранён', 'ok');
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function changeCabinetPassword() {
  const oldPassword = document.getElementById('cab-old-password')?.value;
  const newPassword = document.getElementById('cab-new-password')?.value;
  if (!oldPassword || !newPassword) { toast('✗ Заполните оба поля', 'err'); return; }
  try {
    await api('/auth/change-password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) });
    toast('✓ Пароль изменён', 'ok');
    document.getElementById('cab-old-password').value = '';
    document.getElementById('cab-new-password').value = '';
  } catch (e) {
    toast('✗ Не удалось сменить пароль (проверьте текущий пароль)', 'err');
  }
}

// ═══════ AI STATUS ═══════
async function checkAiStatus() {
  try {
    const s = await api('/ai/status');
    const banner = document.getElementById('ai-rate-banner');
    if (s.rateLimited) {
      const mins = s.remainingMinutes || '?';
      const until = s.cooldownUntilIso ? new Date(s.cooldownUntilIso).toLocaleTimeString('ru-RU', {hour:'2-digit',minute:'2-digit'}) : '?';
      if (banner) {
        banner.innerHTML = `🚫 Лимит AI исчерпан — анализ до ${until} (≈${mins} мин)`;
        banner.style.display = 'block';
      } else {
        const b = document.createElement('div');
        b.id = 'ai-rate-banner';
        b.className = 'ai-rate-banner';
        b.innerHTML = `🚫 Лимит AI исчерпан — анализ до ${until} (≈${mins} мин)`;
        const topbar = document.querySelector('.topbar');
        if (topbar) topbar.prepend(b);
      }
    } else if (banner) {
      banner.style.display = 'none';
    }
  } catch (e) { /* ignore */ }
}

// ═══════ ADMIN PANEL ═══════
async function showAdminModal() {
  let modal = document.getElementById('admin-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'admin-modal';
    modal.className = 'modal-overlay';
    document.body.appendChild(modal);
  }
  try {
    const [users, globalSearches] = await Promise.all([
      api('/admin/users'),
      api('/admin/global-searches'),
    ]);
    renderAdminModal(users, globalSearches);
  } catch (e) {
    toast('✗ Ошибка загрузки данных админки: ' + e.message, 'err');
  }
}

function closeAdminModal() {
  const modal = document.getElementById('admin-modal');
  if (modal) modal.style.display = 'none';
}

function renderAdminModal(users, globalSearches) {
  const modal = document.getElementById('admin-modal');
  const globalCards = (globalSearches || []).map(s => globalSearchCardHtml(s)).join('');
  const rows = users.map(u => `
    <tr>
      <td>${escHtml(u.username)}</td>
      <td>${escHtml(u.displayName)}</td>
      <td>${u.role === 'admin' ? '🛡 admin' : 'user'}</td>
      <td>${escHtml(u.city || '—')}</td>
      <td><span class="status-pill ${u.active ? 'active' : 'inactive'}">${u.active ? 'активен' : 'выключен'}</span></td>
      <td class="admin-actions">
        <button class="provider-btn" onclick="adminResetPassword(${u.id})" title="Сбросить пароль">🔑</button>
        <button class="provider-btn" onclick="adminToggleActive(${u.id}, ${!u.active})" title="${u.active ? 'Деактивировать' : 'Активировать'}">${u.active ? '⏸' : '▶'}</button>
        <button class="provider-btn provider-del" onclick="adminDeleteUser(${u.id})" title="Удалить">✕</button>
      </td>
    </tr>`).join('');

  modal.innerHTML = `
    <div class="modal-box modal-box-wide">
      <div class="modal-head"><h3>🛡 Админка — пользователи</h3><button class="modal-x" onclick="closeAdminModal()">✕</button></div>
      <div class="modal-body settings-body">
        <div style="overflow-x:auto">
          <table class="admin-table">
            <thead><tr><th>Логин</th><th>Имя</th><th>Роль</th><th>Город</th><th>Статус</th><th>Действия</th></tr></thead>
            <tbody>${rows || '<tr><td colspan="6" style="text-align:center;color:var(--muted)">Пока нет пользователей</td></tr>'}</tbody>
          </table>
        </div>

        <div class="settings-separator"><span>+ Новый пользователь</span></div>
        <div class="provider-body" style="grid-template-columns:1fr 1fr">
          <div class="provider-field">
            <label>Логин</label>
            <input class="provider-inp" id="admin-new-username" placeholder="sestra">
          </div>
          <div class="provider-field">
            <label>Имя</label>
            <input class="provider-inp" id="admin-new-displayname" placeholder="Сестра">
          </div>
          <div class="provider-field">
            <label>Город</label>
            <input class="provider-inp" id="admin-new-city" placeholder="Уфа">
          </div>
          <div class="provider-field">
            <label>Роль</label>
            <select class="provider-inp" id="admin-new-role">
              <option value="user" selected>user</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <div class="provider-field provider-field-full">
            <label>Пароль (не заполняйте, чтобы сгенерировать случайный)</label>
            <input class="provider-inp" id="admin-new-password" type="password">
          </div>
        </div>
        <button class="btn btn-second" onclick="adminCreateUser()" style="width:100%;margin-top:8px">+ Создать пользователя</button>

        <div class="settings-separator"><span>🌐 Общие поиски (видны всем пользователям)</span></div>
        <div class="providers-list" id="admin-global-searches-list">
          ${globalCards}
          <button class="btn btn-second" onclick="addGlobalSearchCard()" style="margin-top:8px;width:100%">+ Добавить общий поиск</button>
        </div>
      </div>
      <div class="modal-foot">
        <button class="btn btn-ghost" onclick="closeAdminModal()">Закрыть</button>
      </div>
    </div>`;
  modal.style.display = 'flex';
}

async function adminCreateUser() {
  const username = document.getElementById('admin-new-username')?.value?.trim();
  const displayName = document.getElementById('admin-new-displayname')?.value?.trim();
  const city = document.getElementById('admin-new-city')?.value?.trim();
  const role = document.getElementById('admin-new-role')?.value;
  const password = document.getElementById('admin-new-password')?.value;
  if (!username) { toast('✗ Укажите логин', 'err'); return; }
  try {
    const result = await api('/admin/users', {
      method: 'POST',
      body: JSON.stringify({ username, displayName, city, role, password })
    });
    if (result.generatedPassword) {
      alert(`Пользователь "${username}" создан.\nПароль: ${result.generatedPassword}\n\nСообщите его пользователю — он больше нигде не отображается.`);
    } else {
      toast('✓ Пользователь создан', 'ok');
    }
    showAdminModal();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function adminResetPassword(id) {
  if (!confirm('Сбросить пароль этого пользователя?')) return;
  try {
    const result = await api(`/admin/users/${id}/reset-password`, { method: 'POST' });
    alert(`Новый пароль: ${result.generatedPassword}\n\nСообщите его пользователю — он больше нигде не отображается.`);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function adminToggleActive(id, active) {
  try {
    await api(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify({ active }) });
    toast(active ? '✓ Пользователь активирован' : '✓ Пользователь деактивирован', 'ok');
    showAdminModal();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function adminDeleteUser(id) {
  if (!confirm('Удалить пользователя и все его поиски? Это необратимо.')) return;
  try {
    await api(`/admin/users/${id}`, { method: 'DELETE' });
    toast('✓ Пользователь удалён', 'ok');
    showAdminModal();
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ MOBILE SIDEBAR ═══════
// На узких экранах сайдбар раньше просто скрывался (display:none) — на телефоне
// не было ни статусных фильтров, ни кнопок запуска. Теперь он выезжает по ☰.
function toggleSidebar(force) {
  const open = typeof force === 'boolean' ? force : !document.body.classList.contains('sidebar-open');
  document.body.classList.toggle('sidebar-open', open);
}

// ═══════ INIT ═══════
// startApp вызывается на каждый вход (включая logout → login в той же вкладке);
// обработчики и интервал должны вешаться ровно один раз, иначе каждый повторный
// логин добавлял дубли: по два-три запроса на смену фильтра и лишние таймеры.
let listenersBound = false;

function startApp() {
  document.getElementById('login-view').style.display = 'none';
  document.getElementById('app-root').style.display = '';

  initTheme();
  const startPage = restoreFiltersFromUrl(); // фильтры/страница из адресной строки
  loadStats(); // also populates district filter from topDistricts
  loadJobs(); // populates person/search filters from configured (person, search) jobs
  loadVacancies(startPage);
  checkAiStatus(); // check rate limit status
  syncNotifyButton(); // 🔔/🔕 по реальному состоянию
  resumePipelinePollingIfRunning(); // фоновый запуск мог идти до перезагрузки страницы

  if (listenersBound) return;
  listenersBound = true;

  // Theme buttons
  document.querySelectorAll('.theme-b').forEach(btn => {
    btn.addEventListener('click', () => setTheme(btn.dataset.theme));
  });

  // Navigation
  document.querySelectorAll('.nav-item[data-filter]').forEach(el => {
    el.addEventListener('click', () => setFilter(el.dataset.filter));
  });

  // Filters. Смена человека сначала пересобирает список его поисков и только потом
  // грузит вакансии — раньше запрос уходил со старым searchName другого человека
  // (отдельный listener срабатывал после этого) и список показывал пустоту.
  document.querySelectorAll('.filter-sel').forEach(el => {
    el.addEventListener('change', () => {
      if (el.id === 'person-filter') updateSearchNameOptions();
      loadVacancies(1);
    });
  });
  document.getElementById('search-input')?.addEventListener('input', debounceSearch);

  // Periodic AI status check every 5 min
  setInterval(checkAiStatus, 300000);
}

let currentUser = null;

function showLoginView() {
  currentUser = null;
  document.getElementById('app-root').style.display = 'none';
  document.getElementById('login-view').style.display = 'flex';
  document.querySelectorAll('.modal-overlay').forEach(m => m.style.display = 'none');
  setTimeout(() => document.getElementById('login-username')?.focus(), 50);
}

function applyCurrentUser(user) {
  currentUser = user;
  const badge = document.getElementById('user-badge');
  if (badge) {
    badge.innerHTML = `<b>${escHtml(user.displayName)}</b>${user.role === 'admin' ? '<span class="role-tag">admin</span>' : ''}`;
  }
  document.getElementById('btn-admin')?.classList.toggle('hidden', user.role !== 'admin');
  document.getElementById('btn-settings')?.classList.toggle('hidden', user.role !== 'admin');
  // Переключение уведомлений — admin-only на бэкенде (POST /settings/notifications
  // отвечает 403); обычному пользователю кнопка давала только ошибку.
  document.getElementById('btn-notify-toggle')?.classList.toggle('hidden', user.role !== 'admin');
}

async function doLogin() {
  const username = document.getElementById('login-username')?.value?.trim();
  const password = document.getElementById('login-password')?.value;
  const errorEl = document.getElementById('login-error');
  if (errorEl) errorEl.textContent = '';
  if (!username || !password) {
    if (errorEl) errorEl.textContent = 'Введите логин и пароль';
    return;
  }
  try {
    const user = await api('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
    applyCurrentUser(user);
    startApp();
  } catch (e) {
    if (errorEl) errorEl.textContent = 'Неверный логин или пароль';
  }
}

async function doLogout() {
  try { await api('/auth/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
  showLoginView();
}

async function checkAuthAndInit() {
  try {
    const user = await api('/auth/me');
    applyCurrentUser(user);
    startApp();
  } catch (e) {
    showLoginView();
  }
}

document.addEventListener('DOMContentLoaded', () => {
  checkAuthAndInit();
});
