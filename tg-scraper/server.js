'use strict';

/**
 * Headless-browser sidecar that reads public Telegram channels via
 * web.telegram.org, logged in as a real account.
 *
 * Why web.telegram.org instead of a Telegram Bot API bot or a Telethon/MTProto
 * userbot: a Bot API bot only receives posts from channels where it's been
 * added as admin (won't happen for channels we don't own), and any MTProto
 * client (Telethon, Pyrogram, even TDLib) requires registering an app at
 * my.telegram.org — which was unreachable/blocked for this deployment's
 * network. web.telegram.org is Telegram's own official web client: it ships
 * with its own embedded app credentials, so logging in only needs the normal
 * phone+code flow a person goes through, no app registration at all. Message
 * text is read straight out of the rendered DOM (not OCR/pixels), which is
 * exactly as reliable as the hh.ru scraping this sidecar's sibling (../scraper)
 * already does.
 *
 * Session persistence: launchPersistentContext keeps the logged-in session
 * (IndexedDB/localStorage) on disk across restarts, same pattern as
 * ../scraper/server.js keeps DDoS-Guard cookies. Login only needs to happen
 * once (via /login/start + /login/code), and possibly again if Telegram ever
 * invalidates the session.
 */

const http = require('http');
const path = require('path');
const { URL } = require('url');
const { chromium } = require('playwright');

const PORT = parseInt(process.env.TG_SCRAPER_PORT || '8096', 10);
const HOST = process.env.TG_SCRAPER_HOST || '127.0.0.1';
const NAV_TIMEOUT_MS = parseInt(process.env.TG_SCRAPE_TIMEOUT_MS || '30000', 10);
const PROFILE_DIR = process.env.TG_SCRAPER_PROFILE_DIR || path.join(__dirname, 'profile-data');

let contextPromise = null;
let sharedPage = null;
let queue = Promise.resolve();

/** Serialize all browser interactions through this — one page is reused for everything. */
function enqueue(task) {
  const result = queue.then(task);
  queue = result.catch(() => {});
  return result;
}

// Idle parking: verified live (2026-08-16) that the shared browser, left open on
// web.telegram.org between scrapes, pegged its GPU process at ~100-140% CPU
// continuously for 7+ hours with nothing actually happening. First tried just
// navigating the idle page to about:blank (Playwright disables background-tab
// throttling by default, so a theory was tweb's own animations — the doodle
// background, star-reaction sparkles — just compositing at full rate forever) —
// verified live that this did NOT help at all: CPU stayed pegged for minutes after
// the navigation succeeded. tweb registers a Service Worker (push notifications —
// see the "Never miss a message!" banner), which runs independently of the
// document and survives a plain page.goto() away from it; that's the more likely
// culprit. Closing the whole browser context is the only thing guaranteed to stop
// EVERYTHING regardless of which of the two it actually is — the next request's
// existing getContext()/getPage() lazy-launch (same path a cold service restart
// already takes, proven reliable) relaunches cleanly, still logged in (the
// persisted profile dir holds the session, not the live process).
const IDLE_PARK_MS = 2 * 60 * 1000;
let idleParkTimer = null;

function scheduleIdlePark() {
  if (idleParkTimer) clearTimeout(idleParkTimer);
  idleParkTimer = setTimeout(() => {
    enqueue(async () => {
      if (!contextPromise) return;
      try {
        const context = await contextPromise;
        await context.close();
      } catch (e) { /* best-effort — a failed close just costs CPU until the next attempt */
      } finally {
        contextPromise = null;
        sharedPage = null;
      }
    });
  }, IDLE_PARK_MS);
}

// This is a real logged-in account, not an anonymous scraper — verified live
// that opening several channels back-to-back with zero delay works fine
// technically, but it's exactly the mechanical, perfectly-serialized request
// pattern behavioral anti-abuse detection looks for. Same reasoning and
// constants as ../scraper/server.js's hh.ru pacing.
const CHANNEL_DELAY_MS = parseInt(process.env.TG_SCRAPE_DELAY_MS || '3000', 10);
const CHANNEL_JITTER_MS = parseInt(process.env.TG_SCRAPE_JITTER_MS || '4000', 10);
let lastPacedActionAt = 0;

function nextChannelDelayMs() {
  let delay = CHANNEL_DELAY_MS + Math.random() * CHANNEL_JITTER_MS;
  // Occasionally take a much longer "reading" pause — a person doesn't click
  // through channel after channel at a steady cadence for minutes on end.
  if (Math.random() < 0.07) delay += 15000 + Math.random() * 30000;
  return delay;
}

/** Same serialization as enqueue, plus a human-paced delay since the last
 *  channel read/search — NOT applied to /login/*, which is interactive,
 *  inherently rare, and already as slow as a person typing a code. */
function enqueuePaced(task) {
  return enqueue(async () => {
    const elapsed = Date.now() - lastPacedActionAt;
    const delay = nextChannelDelayMs();
    if (elapsed < delay) {
      await new Promise((r) => setTimeout(r, delay - elapsed));
    }
    try {
      return await task();
    } finally {
      lastPacedActionAt = Date.now();
      scheduleIdlePark();
    }
  });
}

async function getContext() {
  if (!contextPromise) {
    contextPromise = (async () => {
      // Width matters functionally, not just cosmetically: below tweb's responsive
      // breakpoint the chat-list sidebar and the open chat share one pane, and
      // opening a chat slides the sidebar off-screen (translateX) until you're
      // "back" in list view — verified live, every click on the search box or a
      // chat-list row after opening a channel failed with "outside of the
      // viewport" at 900px, and succeeded immediately at 1400px (two-pane mode).
      const context = await chromium.launchPersistentContext(PROFILE_DIR, {
        headless: true,
        locale: 'ru-RU',
        viewport: { width: 1400, height: 1000 },
      });
      // Same reasoning as ../scraper/server.js: navigator.webdriver=true is the
      // cheapest headless tell an anti-bot check can look at.
      await context.addInitScript(() => {
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
      });
      return context;
    })();
    contextPromise.catch(() => { contextPromise = null; });
  }
  return contextPromise;
}

/** If the shared browser died (crash/OOM), drop it so the next request relaunches. */
function resetContextIfCrashed(e) {
  if (/closed|crashed|disconnected/i.test(String(e && e.message))) {
    contextPromise = null;
    sharedPage = null;
  }
}

async function getPage() {
  const context = await getContext();
  if (!sharedPage || sharedPage.isClosed()) {
    sharedPage = context.pages()[0] || (await context.newPage());
  }
  return sharedPage;
}

/**
 * Telegram occasionally shows a "Someone just got access to your messages!"
 * prompt right after a fresh login from a new IP/device fingerprint (headless
 * Chromium's UA reads as an unfamiliar device). Confirming it is what a real
 * user would do to keep the session alive — left unconfirmed, Telegram may
 * treat the session as unrecognized. Text uses a typographic apostrophe
 * (’, not '), so this matches on "Yes" inside the action button instead.
 */
async function confirmNewLoginIfPrompted(page) {
  const yes = page.locator('button:has-text("Yes")');
  if (await yes.count() > 0) {
    await yes.first().click().catch(() => {});
  }
}

async function loginStart(phone) {
  const page = await getPage();
  await page.goto('https://web.telegram.org/k/', { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT_MS });
  await page.waitForTimeout(3000);

  let phoneField = page.locator('.input-field-phone .input-field-input[contenteditable="true"]');
  if (await phoneField.count() === 0) {
    // Fresh profile lands on the QR-code screen first.
    const link = page.getByText('LOG IN BY PHONE NUMBER', { exact: false });
    if (await link.count() > 0) {
      await link.first().click();
      await page.waitForTimeout(1500);
      phoneField = page.locator('.input-field-phone .input-field-input[contenteditable="true"]');
    }
  }
  if (await phoneField.count() === 0) {
    return { ok: false, reason: 'phone_field_not_found' };
  }

  await phoneField.click();
  await phoneField.fill(phone);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.waitForTimeout(3000);

  const codeInput = page.locator('input[autocomplete="one-time-code"]');
  if (await codeInput.count() > 0) return { ok: true, awaiting: 'code' };

  const errorLabel = page.locator('._errorLabel_1b0yp_99');
  const errorText = (await errorLabel.count()) > 0 ? (await errorLabel.first().innerText()).trim() : '';
  return { ok: false, reason: errorText || 'unexpected_screen_after_phone' };
}

async function loginCode(code) {
  const page = await getPage();
  const codeInput = page.locator('input[autocomplete="one-time-code"]');
  if (await codeInput.count() === 0) return { ok: false, reason: 'no_code_screen' };

  await codeInput.click();
  await codeInput.type(code, { delay: 80 });
  await page.waitForTimeout(3000);

  const passwordField = page.locator('input[type="password"]');
  if (await passwordField.count() > 0) return { ok: true, awaiting: 'password' };

  await confirmNewLoginIfPrompted(page);
  return { ok: true, awaiting: 'none' };
}

async function loginPassword(password) {
  const page = await getPage();
  const passwordField = page.locator('input[type="password"]');
  if (await passwordField.count() === 0) return { ok: false, reason: 'no_password_screen' };

  await passwordField.click();
  await passwordField.fill(password);
  await page.getByRole('button', { name: 'Next' }).click();
  await page.waitForTimeout(3000);

  await confirmNewLoginIfPrompted(page);
  return { ok: true, awaiting: 'none' };
}

async function collectMessages(page, channel) {
  const raw = await page.evaluate(async () => {
    // Views/reactions: rendered reaction pills are sticker images referenced only by
    // an opaque data-doc-id (Telegram Web K renders even standard emoji reactions
    // through its sticker pipeline) — verified live that the doc-id has no emoji
    // identity anywhere in the DOM (no alt text, no title, no data-emoji). The raw
    // MTProto message object DOES carry it plainly (reactions.results[].reaction.
    // emoticon), and Telegram Web K exposes its own internal managers on
    // window.appImManager for exactly this kind of read — undocumented and private
    // to this client build, so this is inherently more fragile than the public DOM
    // text scraping above and needs to fail soft (null views / empty reactions),
    // never break message collection over it.
    const chat = window.appImManager && window.appImManager.chat;
    const managers = chat && chat.managers;
    const peerId = chat && chat.peerId;

    const nodes = Array.from(document.querySelectorAll('.bubble.channel-post[data-mid]'));
    const out = [];
    for (const n of nodes) {
      const messageEl = n.querySelector('.message.spoilers-container');
      // <reactions-element> (reaction pills AND the views/timestamp span, both — see
      // the live-verified markup dump) is a direct child of messageEl, not a sibling
      // outside it — innerText picked up its text too, appending stray digits/glyphs
      // ("1\n3\n\n21:56") onto every post's stored description. Read from a
      // clone with it stripped instead of the live element; ts/views/reactions are
      // already read separately (data-timestamp attribute, appMessagesManager above).
      let cleanMessageEl = messageEl;
      if (messageEl) {
        cleanMessageEl = messageEl.cloneNode(true);
        cleanMessageEl.querySelectorAll('reactions-element').forEach((el) => el.remove());
      }
      const text = cleanMessageEl ? cleanMessageEl.innerText || '' : '';
      // innerText drops href attributes — a link whose visible label is just
      // "Посмотреть вакансию полностью" (verified live on the kadrout channel)
      // leaves the actual URL nowhere in the plain text at all. Anchor hrefs are
      // appended as a separate trailing line so the downstream URL-extraction
      // regex (Java side) can still find them, without disturbing the visible text.
      const hrefs = cleanMessageEl
        ? Array.from(cleanMessageEl.querySelectorAll('a[href]'))
            .map((a) => a.getAttribute('href'))
            .filter((h) => h && /^https?:\/\//i.test(h) && !/^https?:\/\/t\.me\//i.test(h))
        : [];
      const uniqueHrefs = [...new Set(hrefs)];

      let views = null;
      let reactions = {};
      if (managers && peerId != null) {
        try {
          const msg = await managers.appMessagesManager.getMessageByPeer(peerId, Number(n.getAttribute('data-mid')));
          if (msg) {
            if (typeof msg.views === 'number') views = msg.views;
            const results = msg.reactions && msg.reactions.results;
            if (Array.isArray(results)) {
              for (const r of results) {
                if (r.reaction && r.reaction.emoticon && typeof r.count === 'number') {
                  reactions[r.reaction.emoticon] = r.count;
                }
              }
            }
          }
        } catch (e) { /* best-effort — see comment above */ }
      }

      out.push({
        mid: n.getAttribute('data-mid'),
        ts: n.getAttribute('data-timestamp'),
        text: uniqueHrefs.length ? `${text}\n\n${uniqueHrefs.join('\n')}` : text,
        views,
        reactions,
      });
    }
    return out;
  });
  return raw
    .filter((r) => r.text && r.text.trim().length > 0)
    .map((r) => {
      // web.telegram.org's internal message id packs the real channel post id
      // into the low 32 bits (verified live: data-mid 4294977617 = real id 10321
      // OR'd with 1<<32) — the real id is what t.me/<channel>/<id> links need.
      const realId = (BigInt(r.mid) & 0xffffffffn).toString();
      return {
        id: `tg_${channel}_${realId}`,
        text: r.text.trim(),
        publishedAt: new Date(parseInt(r.ts, 10) * 1000).toISOString(),
        link: `https://t.me/${channel}/${realId}`,
        channel,
        source: 'telegram',
        views: r.views,
        reactions: r.reactions,
      };
    });
}

/**
 * Opens a channel the way a person actually would: through the sidebar search,
 * clicking the exact-username result row. NOT via `page.goto(#@username)` —
 * verified live that tweb's router only reads location.hash once, at initial
 * page bootstrap; it doesn't listen for hashchange, so a second/third goto to
 * a different hash just rewrites the address bar without switching chats (the
 * message list silently keeps showing whatever was already open). A search +
 * click goes through the app's real in-memory routing instead, and reliably
 * switches every time.
 */
async function openChannel(page, channel) {
  // Opening a chat slides the sidebar off-screen in single-pane mode (see the
  // viewport comment in getContext) — clicking it back into view first is what
  // makes the search box reachable on the 2nd+ channel in a session.
  const backBtn = page.locator('.sidebar-back-button');
  if (await backBtn.count() > 0) {
    await backBtn.first().click({ force: true }).catch(() => {});
    await page.waitForTimeout(500);
  }

  const searchBox = page.locator('.main-search-sidebar-header input.input-search-input');
  await searchBox.click({ force: true });
  await searchBox.fill('');
  await searchBox.fill(`@${channel}`);
  await page.waitForTimeout(2000);

  const rows = await page.$$('a.chatlist-chat');
  for (const row of rows) {
    const subtitle = await row.$eval('.row-subtitle', (el) => el.textContent).catch(() => '');
    if (subtitle && subtitle.toLowerCase().startsWith(`@${channel.toLowerCase()},`)) {
      await row.click({ force: true });
      return true;
    }
  }
  return false;
}

/**
 * Public channels render without joining (a "SUBSCRIBE" bar shows at the
 * bottom, but the message list above it is already the real thing) — no
 * join/subscribe step needed to read posts.
 */
async function scrapeChannel(channel, { limit = 50 } = {}) {
  const page = await getPage();
  if (!/telegram\.org/.test(page.url())) {
    await page.goto('https://web.telegram.org/k/', { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT_MS });
    await page.waitForTimeout(3000);
  }

  const opened = await openChannel(page, channel);
  if (!opened) return { ok: false, reason: 'channel_not_found' };
  await page.waitForTimeout(3000);

  let messages = await collectMessages(page, channel);
  let lastCount = -1;
  let tries = 0;
  // Scroll the message list to the top repeatedly to load more history —
  // it's virtualized, so older posts simply aren't in the DOM until scrolled to.
  while (messages.length < limit && messages.length !== lastCount && tries < 15) {
    lastCount = messages.length;
    await page.evaluate(() => {
      const el = document.querySelector('.bubbles-scrollable');
      if (el) el.scrollTop = 0;
    });
    await page.waitForTimeout(1200);
    messages = await collectMessages(page, channel);
    tries++;
  }

  messages.sort((a, b) => a.publishedAt.localeCompare(b.publishedAt));
  return { ok: true, items: messages.slice(-limit) };
}

/**
 * Drives Telegram's own global search (the same "Channels" tab a person
 * would use) to discover real, currently-existing public channels for a
 * query — built after discovering that a hand-curated candidate list
 * (from an earlier, unverified t.me/s/-preview pass) was mostly usernames
 * that don't exist or had been renamed.
 */
async function searchChannels(query) {
  const page = await getPage();
  await page.goto('https://web.telegram.org/k/', { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT_MS });
  await page.waitForTimeout(2500);

  // Same sidebar-off-screen issue as openChannel — a chat left open from an
  // earlier /channel call in this same long-lived page needs the sidebar
  // brought back before its search box is reachable.
  const backBtn = page.locator('.sidebar-back-button');
  if (await backBtn.count() > 0) {
    await backBtn.first().click({ force: true }).catch(() => {});
    await page.waitForTimeout(500);
  }

  const searchBox = page.locator('.main-search-sidebar-header input.input-search-input');
  if (await searchBox.count() === 0) return { ok: false, reason: 'search_box_not_found' };
  await searchBox.click({ force: true });
  await searchBox.fill('');
  await searchBox.fill(query);
  await page.waitForTimeout(2000);

  // The tab label span sits inside a wrapper div that actually receives the
  // click (verified live: a bare click on the span times out with "intercepts
  // pointer events" from the parent .menu-horizontal-div-item) — force it.
  const channelsTab = page.getByText('Channels', { exact: true });
  if (await channelsTab.count() > 0) {
    await channelsTab.first().click({ force: true });
    await page.waitForTimeout(2000);
  }

  const showMore = page.getByText('show more', { exact: false });
  if (await showMore.count() > 0) {
    await showMore.first().click({ force: true }).catch(() => {});
    await page.waitForTimeout(1500);
  }

  const rows = await page.$$eval('a.chatlist-chat', (nodes) =>
    nodes.map((n) => {
      const subtitle = n.querySelector('.row-subtitle')?.innerText || '';
      const title = n.querySelector('.peer-title')?.innerText || '';
      return { subtitle, title };
    })
  );

  const items = rows
    .map(({ subtitle, title }) => {
      const m = subtitle.match(/@(\w+),\s*([\d\s]+)\s*subscribers/);
      if (!m) return null;
      return { username: m[1], title, subscribers: parseInt(m[2].replace(/\s/g, ''), 10) };
    })
    .filter(Boolean);

  return { ok: true, items };
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1e6) req.destroy();
    });
    req.on('end', () => {
      if (!body) return resolve({});
      try {
        resolve(JSON.parse(body));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(payload));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === '/health') {
    sendJson(res, 200, { ok: true });
    return;
  }

  if (url.pathname === '/login/start' && req.method === 'POST') {
    readJsonBody(req)
      .then(({ phone }) => {
        if (!phone) return sendJson(res, 400, { ok: false, reason: 'missing_phone' });
        return enqueue(() => loginStart(phone))
          .then((result) => sendJson(res, result.ok ? 200 : 502, result))
          .catch((e) => {
            resetContextIfCrashed(e);
            sendJson(res, 500, { ok: false, reason: `unhandled: ${e.message}` });
          });
      })
      .catch(() => sendJson(res, 400, { ok: false, reason: 'bad_json' }));
    return;
  }

  if (url.pathname === '/login/code' && req.method === 'POST') {
    readJsonBody(req)
      .then(({ code }) => {
        if (!code) return sendJson(res, 400, { ok: false, reason: 'missing_code' });
        return enqueue(() => loginCode(code))
          .then((result) => sendJson(res, result.ok ? 200 : 502, result))
          .catch((e) => {
            resetContextIfCrashed(e);
            sendJson(res, 500, { ok: false, reason: `unhandled: ${e.message}` });
          });
      })
      .catch(() => sendJson(res, 400, { ok: false, reason: 'bad_json' }));
    return;
  }

  if (url.pathname === '/login/password' && req.method === 'POST') {
    readJsonBody(req)
      .then(({ password }) => {
        if (!password) return sendJson(res, 400, { ok: false, reason: 'missing_password' });
        return enqueue(() => loginPassword(password))
          .then((result) => sendJson(res, result.ok ? 200 : 502, result))
          .catch((e) => {
            resetContextIfCrashed(e);
            sendJson(res, 500, { ok: false, reason: `unhandled: ${e.message}` });
          });
      })
      .catch(() => sendJson(res, 400, { ok: false, reason: 'bad_json' }));
    return;
  }

  if (url.pathname === '/channel') {
    const channel = url.searchParams.get('username');
    const limit = parseInt(url.searchParams.get('limit') || '50', 10);
    if (!channel) return sendJson(res, 400, { ok: false, reason: 'missing_username' });

    const started = Date.now();
    enqueuePaced(() => scrapeChannel(channel, { limit }))
      .then((result) => {
        console.log(`[channel] ${channel} ${result.ok ? `ok items=${result.items.length}` : `fail:${result.reason}`} ${Date.now() - started}ms`);
        sendJson(res, result.ok ? 200 : 502, result);
      })
      .catch((e) => {
        resetContextIfCrashed(e);
        console.log(`[channel] ${channel} unhandled:${e.message} ${Date.now() - started}ms`);
        sendJson(res, 500, { ok: false, reason: `unhandled: ${e.message}` });
      });
    return;
  }

  if (url.pathname === '/search-channels') {
    const query = url.searchParams.get('q');
    if (!query) return sendJson(res, 400, { ok: false, reason: 'missing_q' });

    const started = Date.now();
    enqueuePaced(() => searchChannels(query))
      .then((result) => {
        console.log(`[search-channels] "${query}" ${result.ok ? `ok items=${result.items.length}` : `fail:${result.reason}`} ${Date.now() - started}ms`);
        sendJson(res, result.ok ? 200 : 502, result);
      })
      .catch((e) => {
        resetContextIfCrashed(e);
        console.log(`[search-channels] "${query}" unhandled:${e.message} ${Date.now() - started}ms`);
        sendJson(res, 500, { ok: false, reason: `unhandled: ${e.message}` });
      });
    return;
  }

  sendJson(res, 404, { ok: false, reason: 'not_found' });
});

server.listen(PORT, HOST, () => {
  console.log(`tg-scraper listening on ${HOST}:${PORT}, profile dir: ${PROFILE_DIR}`);
});

// Without this, `systemctl restart` (every deploy) SIGTERMs a process with no
// handler for it — Node's default action is to die immediately, abandoning
// Chromium mid-write to the persistent profile's IndexedDB (the logged-in
// session). Verified live: one deploy left the process unresponsive to SIGTERM
// for the full 90s stop timeout, and systemd SIGKILLed it — the session
// happened to survive that time, but an unclean kill of IndexedDB has no such
// guarantee on the next one. Closing the browser context first lets Playwright
// flush it properly.
async function shutdown() {
  console.log('Shutting down...');
  server.close();
  if (contextPromise) {
    const context = await contextPromise.catch(() => null);
    if (context) await context.close().catch(() => {});
  }
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
