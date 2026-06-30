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

function statusLabel(status) {
  const map = { new: 'Новые', favorite: 'Избранное', applied: 'Отклик', rejected: 'Отклонено', fraud: 'Обман' };
  return map[status] || status || '—';
}

// ═══════ STATS ═══════
async function loadStats() {
  try {
    const s = await api('/stats');
    document.getElementById('cnt-all').textContent = s.total || 0;
    document.getElementById('cnt-new').textContent = s.byStatus?.newPending || 0;
    document.getElementById('cnt-pending').textContent = s.byStatus?.pending || 0;
    document.getElementById('cnt-favorite').textContent = s.byStatus?.favorite || 0;
    document.getElementById('cnt-applied').textContent = s.byStatus?.applied || 0;
    document.getElementById('cnt-rejected').textContent = s.byStatus?.rejected || 0;
    document.getElementById('cnt-fraud').textContent = s.byStatus?.fraud || 0;

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
  } catch (e) {
    console.error('Stats error:', e);
  }
}

// ═══════ VACANCY LIST ═══════
async function loadVacancies(page = 1) {
  currentPage = page;
  const params = new URLSearchParams();
  params.set('page', page);
  params.set('perPage', 30);

  if (currentFilter !== 'all') {
    if (currentFilter === 'pending') params.set('status', 'new');
    else params.set('status', currentFilter);
  }

  const district = document.getElementById('district-filter')?.value;
  if (district) params.set('district', district);

  const minSalary = document.getElementById('salary-filter')?.value;
  if (minSalary) params.set('minSalary', minSalary);

  const minScore = document.getElementById('score-filter')?.value;
  if (minScore) params.set('minScore', minScore);

  const remote = document.getElementById('remote-filter')?.value;
  if (remote) params.set('isRemote', remote === 'true');

  const sort = document.getElementById('sort-filter')?.value;
  if (sort) params.set('sort', sort);

  const search = document.getElementById('search-input')?.value;
  if (search) params.set('search', search);

  try {
    const data = await api('/vacancies?' + params);
    renderList(data.vacancies || []);
    renderPagination(data);
  } catch (e) {
    console.error('Load error:', e);
  }
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
    const scoreCls = scoreClass(score);

    let statusChip = '';
    if (v.aiVerdict === 'fraud') {
      statusChip = `<span class="chp chp-fr">🚫 Обман</span>`;
    } else if (v.status === 'favorite') {
      statusChip = `<span class="chp chp-fv">⭐</span>`;
    } else if (v.status === 'applied') {
      statusChip = `<span class="chp chp-app">📤</span>`;
    } else if (v.status === 'rejected') {
      statusChip = `<span class="chp chp-rej">❌</span>`;
    } else if (!v.aiVerdict || v.aiVerdict === 'pending') {
      statusChip = `<span class="chp chp-nw">Новое</span>`;
    }

    const tags = (v.tags || []).slice(0, 3).map(t => `<span class="chp">${escHtml(t)}</span>`).join('');
    const hasSalary = v.salaryFrom || v.salaryTo;
    const salClass = hasSalary ? '' : 'no-sal';
    const salText = hasSalary
      ? ((v.salaryFrom && v.salaryTo) ? `${fmtN(v.salaryFrom)}–${fmtN(v.salaryTo)}К ₽` : (v.salaryFrom ? `от ${fmtN(v.salaryFrom)}К` : `до ${fmtN(v.salaryTo)}К`))
      : 'не указана';

    return `
      <div class="vacancy ${isSelected}" onclick="openDetail(${v.id})">
        <div class="cb ${v._checked ? 'checked' : ''}" onclick="event.stopPropagation(); toggleCheck(${v.id})"></div>
        <div class="v-main">
          <div class="v-top">
            <div class="v-title">${escHtml(v.title)}</div>
            <div class="v-sal ${salClass}">${salText}</div>
          </div>
          <div class="v-meta">
            <span>${escHtml(v.company || '')}</span>
            <span class="dot"></span>
            <span>${v.isRemote ? '🌐 Удалёнка' : ('📍 ' + (v.district || '—'))}</span>
            <span class="dot"></span>
            <span>🕐${formatDate(v.publishedAt || v.createdAt)}</span>
          </div>
          <div class="v-footer">
            <span class="pill ${scoreCls}"><span class="s-dot"></span>${score}</span>
            ${statusChip}
            ${tags}
          </div>
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
  const card = document.querySelector(`.vacancy[onclick="openDetail(${id})"]`);
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
  const scoreCls = scoreClass(score);
  const isFraud = v.aiVerdict === 'fraud';
  const isPending = !v.aiVerdict || v.aiVerdict === 'pending';

  // Stats strip
  const hasSalary = v.salaryFrom || v.salaryTo;
  const salDisplay = hasSalary
    ? ((v.salaryFrom && v.salaryTo) ? `${fmtN(v.salaryFrom)}–${fmtN(v.salaryTo)}К` : (v.salaryFrom ? `от ${fmtN(v.salaryFrom)}К` : `до ${fmtN(v.salaryTo)}К`))
    : 'не указана';
  const salStatCls = hasSalary ? '' : 'no-data';

  // Type tag
  const typeTag = isFraud
    ? '<div class="dtype fraud">⚠️ Обнаружен скамо-паттерн</div>'
    : `<div class="dtype">${v.isRemote ? '🌐 Удалёнка' : '🏢 Офис'}</div>`;

  // Tags
  const tags = (v.tags || []).map(t => `<span class="chp" style="cursor:default">${escHtml(t)}</span>`).join('');

  // AI section
  // Build ai-section
  let aiSection;
  if (isFraud) {
    // Fraud: prominent reason box
    aiSection = `
      <div class="fraud-box">
        <div class="fb-inner">
          <div class="fb-head"><span class="fb-badge">🚫 SCAM</span><span class="fb-title">Причина определения как обман</span></div>
          <div class="fb-text">
            ${v.aiReason ? `<div class="fb-reason"><strong>🔍 Что обнаружил AI:</strong><br>${escHtml(v.aiReason)}</div>` : ''}
          </div>
        </div>
      </div>
      <div class="ai-line">
        <span class="ai-sc" style="color:var(--red)">🧠 0/100</span>
        <span>Verdict: <strong>fraud</strong> · повторный анализ не требуется</span>
      </div>`;
  } else if (!isPending && v.aiReason) {
    // Has AI analysis with reason
    const verdictMap = { yes: ['verdict-yes', '✓ Подходит'], no: ['verdict-no', '✗ Не подходит'] };
    const [vdClass, vdText] = verdictMap[v.aiVerdict] || ['verdict-no', v.aiVerdict];
    aiSection = `
      <div class="d-ai-box">
        <div class="d-ai-head">
          <span class="ai-ico">🧠</span>
          <span class="ai-title">Анализ AI</span>
          <div class="d-ai-score" style="color:var(--${score >= 60 ? 'green' : score >= 40 ? 'orange' : 'red'})">${score} / 100</div>
        </div>
        <div class="d-ai-body">
          <div class="d-ai-reason">${escHtml(v.aiReason)}</div>
          <div class="d-ai-verdict ${vdClass}">${vdText}${v.aiVerdict === 'yes' ? ' (score >= 60)' : ''}</div>
        </div>
      </div>`;
  } else if (!isPending) {
    aiSection = `
      <div class="d-ai-box">
        <div class="d-ai-head">
          <span class="ai-ico">🧠</span>
          <span class="ai-title">Анализ AI</span>
          <div class="d-ai-score" style="color:var(--${score >= 60 ? 'green' : score >= 40 ? 'orange' : 'red'})">${score} / 100</div>
        </div>
        <div class="d-ai-body">
          <div class="d-ai-verdict ${v.aiVerdict === 'yes' ? 'verdict-yes' : 'verdict-no'}">${v.aiVerdict === 'yes' ? '✓ Подходит' : '✗ Не подходит'}</div>
        </div>
      </div>`;
  } else {
    aiSection = `<div class="d-ai-box"><div class="d-ai-body"><div class="d-ai-verdict verdict-pending">⏳ Ожидает AI-анализа</div></div></div>`;
  }

  container.innerHTML = `
    <div class="dh">
      <div class="db">Все › <span>${escHtml(statusLabel(v.status))}</span></div>
      ${typeTag}
      <div class="dtitle">${escHtml(v.title)}</div>
      <div class="dcomp">
        🏢 ${escHtml(v.company || '')}
        <span>·</span>
        ${v.isRemote ? '🌐 Удалёнка' : ('📍 ' + escHtml(v.district || '—'))}
        ${v.url ? `· <a class="durl" href="${escHtml(v.url)}" target="_blank">hh.ru ↗</a>` : ''}
      </div>
    </div>

    <div class="d-stats">
      <div class="ds sal ${salStatCls}"><div class="val">${salDisplay}</div><div class="lbl">Зарплата</div></div>
      <div class="ds"><div class="val" style="color:var(--${score >= 60 ? 'green' : score >= 40 ? 'orange' : score > 0 ? 'red' : 'muted'});">${score}</div><div class="lbl">Скор AI</div></div>
      <div class="ds"><div class="val">${escHtml(v.district || (v.isRemote ? 'Удалёнка' : '—'))}</div><div class="lbl">Район</div></div>
    </div>

    <div class="dacts">
      <button class="act act-fav" onclick="setStatus(${v.id}, 'favorite')">⭐ Избранное</button>
      <button class="act act-app" onclick="setStatus(${v.id}, 'applied')">📤 Отклик</button>
      <button class="act act-rej" onclick="setStatus(${v.id}, 'rejected')">❌ Отклонить</button>
      <button class="act act-fra" onclick="setStatus(${v.id}, 'fraud')">🚫 Обман</button>
      ${v.url ? `<a class="act act-lnk" href="${escHtml(v.url)}" target="_blank">🔗 HH.ru</a>` : ''}
    </div>

    ${aiSection}

    <div class="d-sec">
      <h3>Детали</h3>
      <div class="it">
        <div class="ir"><div class="it-t">Работодатель</div><div class="it-v">${escHtml(v.company || '—')}</div></div>
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

function toggleCheck(id) {
  // placeholder for bulk selection
}

// ═══════ FILTERS ═══════
function setFilter(filter) {
  currentFilter = filter;
  document.querySelectorAll('.nav-item[data-filter]').forEach(el => {
    el.classList.toggle('active', el.dataset.filter === filter);
  });
  loadVacancies(1);
}

function debounceSearch() {
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => loadVacancies(1), 300);
}

// ═══════ PIPELINE ═══════
async function runPipeline() {
  toast('▶️ Запуск пайплайна...');
  try {
    const r = await api('/pipeline/run', { method: 'POST' });
    toast(`✓ Собрано: ${r.collected}, новых: ${r.newVacancies}, проанализировано: ${r.analyzed}`, 'ok');
    loadStats();
    loadVacancies(1);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

async function showReanalyzeModal() {
  if (!confirm('Переоценить все вакансии? Это займёт время.')) return;
  toast('🔄 Переоценка запущена...');
  try {
    const r = await api('/pipeline/reanalyze', { method: 'POST' });
    toast(`✓ Сброшено: ${r.reset}, проанализировано: ${r.analyzed}`, 'ok');
    loadStats();
    loadVacancies(1);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

function exportData() {
  window.open('/api/vacancies?status=all&perPage=99999');
}

async function toggleNotifications() {
  try {
    const s = await api('/settings/notifications');
    const newState = !s.enabled;
    await api('/settings/notifications', { method: 'POST', body: JSON.stringify({ enabled: newState }) });
    toast(`${newState ? '🔔' : '🔕'} Уведомления ${newState ? 'включены' : 'отключены'}`);
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

// ═══════ SETTINGS ═══════
let settingsAuthed = false;
let settingsDescriptors = [];

async function showSettingsModal() {
  let modal = document.getElementById('settings-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'settings-modal';
    modal.className = 'modal-overlay';
    document.body.appendChild(modal);
  }

  if (!settingsAuthed) {
    modal.innerHTML = `
      <div class="modal-box">
        <div class="modal-head"><h3>⚙️ Настройки</h3><button class="modal-x" onclick="closeSettingsModal()">✕</button></div>
        <div class="modal-body">
          <p style="color:var(--muted);margin-bottom:16px">Введите пароль для доступа к настройкам</p>
          <input type="password" id="settings-pw" class="search-inp" placeholder="Пароль" style="width:100%;margin-bottom:12px"
                 onkeydown="if(event.key==='Enter')settingsLogin()">
          <button class="btn btn-prim" onclick="settingsLogin()" style="width:100%">Войти</button>
        </div>
      </div>`;
    modal.style.display = 'flex';
    setTimeout(() => document.getElementById('settings-pw')?.focus(), 100);
    return;
  }

  // Authed — load settings
  try {
    const data = await api('/settings?password=1102');
    settingsDescriptors = data.descriptors || [];
    renderSettingsForm(data.values || {});
  } catch (e) {
    toast('✗ Ошибка загрузки настроек: ' + e.message, 'err');
  }
}

function renderSettingsForm(values) {
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
      <div class="modal-body settings-body">${html}</div>
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

async function settingsLogin() {
  const pw = document.getElementById('settings-pw')?.value;
  if (!pw) return;
  try {
    const r = await api('/settings/auth', { method: 'POST', body: JSON.stringify({ password: pw }) });
    if (r.valid) {
      settingsAuthed = true;
      showSettingsModal(); // reload with settings
    } else {
      toast('✗ Неверный пароль', 'err');
    }
  } catch (e) {
    toast('✗ ' + e.message, 'err');
  }
}

function closeSettingsModal() {
  const modal = document.getElementById('settings-modal');
  if (modal) modal.style.display = 'none';
}

async function saveSettings() {
  const modal = document.getElementById('settings-modal');
  const updates = { password: '1102' };

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
        // Custom value
        const custom = modal.querySelector(`[data-key="${key}_custom"]`);
        if (custom) {
          updates[key] = custom.type === 'number' ? parseInt(custom.value) : custom.value;
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

// ═══════ DISTRICT FILTER ═══════
async function loadDistricts() {
  try {
    const s = await api('/stats');
    const select = document.getElementById('district-filter');
    if (!select) return;
    const current = select.value;
    const districts = (s.topDistricts || []).map(d => d.name).filter(n => n);
    select.innerHTML = '<option value="">Все районы</option>' +
      districts.map(d => `<option value="${escHtml(d)}">${escHtml(d)}</option>`).join('') +
      '<option value="Шакша">Шакша</option>' +
      '<option value="Центр">Центр</option>';
    if (current) select.value = current;
  } catch (e) { /* ignore */ }
}

// ═══════ INIT ═══════
document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  loadStats(); // also populates district filter from topDistricts
  loadVacancies(1);

  // Theme buttons
  document.querySelectorAll('.theme-b').forEach(btn => {
    btn.addEventListener('click', () => setTheme(btn.dataset.theme));
  });

  // Navigation
  document.querySelectorAll('.nav-item[data-filter]').forEach(el => {
    el.addEventListener('click', () => setFilter(el.dataset.filter));
  });

  // Filters
  document.querySelectorAll('.filter-sel').forEach(el => {
    el.addEventListener('change', () => loadVacancies(1));
  });
  document.getElementById('search-input')?.addEventListener('input', debounceSearch);
});
