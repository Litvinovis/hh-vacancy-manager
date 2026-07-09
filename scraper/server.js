'use strict';

/**
 * Headless-browser sidecar for fetching full hh.ru vacancy pages.
 *
 * Why this exists: api.hh.ru and plain HTTP requests to hh.ru/vacancy/{id}
 * are both blocked by DDoS-Guard (TLS/behavioral bot-check — no header ever
 * fixes this). A real headless-Chromium session passes cleanly. RSS is the
 * only endpoint hh.ru leaves open for simple HTTP clients, and RSS never
 * contains the actual job description — only company/date/region/salary
 * metadata. So: RSS for discovery (done in the Java app), this service for
 * the real content.
 *
 * One persistent browser instance is kept warm and reused across requests
 * (a fresh launch per request costs ~1-2s and this personal-scale deployment
 * doesn't need per-request isolation). Requests are serialized with a
 * minimum delay between page loads — this is real browser traffic passing
 * an anti-bot gate; the goal is to keep behaving like a person reading one
 * page at a time, not to maximize throughput.
 */

const http = require('http');
const path = require('path');
const { URL } = require('url');
const { chromium } = require('playwright');

const PORT = parseInt(process.env.SCRAPER_PORT || '8095', 10);
const HOST = process.env.SCRAPER_HOST || '127.0.0.1';
const MIN_DELAY_MS = parseInt(process.env.SCRAPE_DELAY_MS || '4000', 10);
// Random extra delay on top of MIN_DELAY_MS — a perfectly constant interval
// between page loads is itself a bot signature.
const JITTER_MS = parseInt(process.env.SCRAPE_JITTER_MS || '4000', 10);
const NAV_TIMEOUT_MS = parseInt(process.env.SCRAPE_TIMEOUT_MS || '20000', 10);
// Persistent browser profile: DDoS-Guard clearance cookies and hh.ru session
// cookies accumulate here across requests AND restarts, so every page load
// looks like the same returning visitor instead of a cold client hitting a
// deep /vacancy/{id} URL with no cookies at all.
const PROFILE_DIR = process.env.SCRAPER_PROFILE_DIR || path.join(__dirname, 'profile-data');

let contextPromise = null;
let queue = Promise.resolve();
let lastScrapeAt = 0;

/**
 * Playwright's bundled Chromium version drifts with every playwright upgrade;
 * a hardcoded "Chrome/120" UA string diverging from the real engine version
 * (Client Hints, JS feature set) is a classic detection signal. Derive the UA
 * major version from the actual browser once at startup instead.
 */
async function buildUserAgent() {
  const probe = await chromium.launch({ headless: true });
  const major = probe.version().split('.')[0];
  await probe.close();
  return `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

async function getContext() {
  if (!contextPromise) {
    contextPromise = (async () => {
      const userAgent = await buildUserAgent();
      const context = await chromium.launchPersistentContext(PROFILE_DIR, {
        headless: true,
        userAgent,
        locale: 'ru-RU',
        viewport: { width: 1366, height: 768 },
      });
      // Playwright exposes navigator.webdriver=true by default — the cheapest
      // headless check an anti-bot script can run.
      await context.addInitScript(() => {
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
      });
      // Skip heavy assets: pages load 2-3x faster and use far less bandwidth.
      // JS and CSS are deliberately NOT blocked — their absence changes page
      // behavior and is itself fingerprintable.
      await context.route('**/*', (route) => {
        const type = route.request().resourceType();
        if (type === 'image' || type === 'media' || type === 'font') return route.abort();
        return route.continue();
      });
      return context;
    })();
    contextPromise.catch(() => { contextPromise = null; }); // failed launch → retry on next call
  }
  return contextPromise;
}

/** If the shared browser died (crash/OOM), drop it so the next request relaunches. */
function resetContextIfCrashed(e) {
  if (/closed|crashed|disconnected/i.test(String(e && e.message))) {
    contextPromise = null;
  }
}

function nextDelayMs() {
  let delay = MIN_DELAY_MS + Math.random() * JITTER_MS;
  // Occasionally take a much longer "reading" pause — humans don't click
  // through listings at a steady cadence for minutes on end.
  if (Math.random() < 0.07) delay += 15000 + Math.random() * 30000;
  return delay;
}

/** Serialize all scrape calls through this so concurrent HTTP requests don't fire off parallel page loads. */
function enqueue(task) {
  const result = queue.then(async () => {
    const elapsed = Date.now() - lastScrapeAt;
    const delay = nextDelayMs();
    if (elapsed < delay) {
      await new Promise((r) => setTimeout(r, delay - elapsed));
    }
    try {
      return await task();
    } finally {
      lastScrapeAt = Date.now();
    }
  });
  // Keep the queue alive even if this task rejects.
  queue = result.catch(() => {});
  return result;
}

/**
 * Polls locator.count() until it stops changing across consecutive checks (or maxWaitMs
 * elapses) — for pages whose content renders in more than one wave, where a single fixed
 * wait can catch it mid-render.
 */
async function waitForStableCount(page, selector, { pollMs = 400, stableChecks = 2, maxWaitMs = 6000 } = {}) {
  let stable = 0;
  let lastCount = -1;
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    await page.waitForTimeout(pollMs);
    const count = await page.locator(selector).count();
    if (count === lastCount) {
      stable++;
      if (stable >= stableChecks) return count;
    } else {
      stable = 0;
      lastCount = count;
    }
  }
  return lastCount;
}

function parseSalary(rawText) {
  if (!rawText) return { salaryFrom: null, salaryTo: null, currency: null, gross: null };
  const numbers = (rawText.match(/[\d \s]{3,}/g) || [])
    .map((s) => parseInt(s.replace(/[ \s]/g, ''), 10))
    .filter((n) => !Number.isNaN(n));

  let salaryFrom = null;
  let salaryTo = null;
  if (numbers.length === 1) {
    if (/^\s*до\b/i.test(rawText.trim())) salaryTo = numbers[0];
    else salaryFrom = numbers[0];
  } else if (numbers.length >= 2) {
    [salaryFrom, salaryTo] = numbers;
  }

  let currency = null;
  if (rawText.includes('₽') || /руб/i.test(rawText)) currency = 'RUR';
  else if (rawText.includes('$')) currency = 'USD';
  else if (rawText.includes('€')) currency = 'EUR';

  let gross = null;
  if (/до вычета/i.test(rawText)) gross = true;
  else if (/на руки/i.test(rawText)) gross = false;

  return { salaryFrom, salaryTo, currency, gross };
}

/**
 * Salary from the JobPosting JSON-LD "baseSalary" object (MonetaryAmount with a
 * QuantitativeValue). Returns null when absent/empty so the caller can fall back
 * to parsing the rendered DOM text. gross is always null here — JSON-LD carries
 * no "до вычета"/"на руки" distinction.
 */
function salaryFromLd(ld) {
  const value = ld && ld.baseSalary && ld.baseSalary.value;
  if (!value) return null;
  const toInt = (x) => {
    const n = parseInt(x, 10);
    return Number.isNaN(n) ? null : n;
  };
  const salaryFrom = toInt(value.minValue != null ? value.minValue : value.value);
  const salaryTo = toInt(value.maxValue);
  if (salaryFrom == null && salaryTo == null) return null;
  let currency = ld.baseSalary.currency || null;
  if (currency === 'RUB') currency = 'RUR'; // the rest of the app uses hh.ru's own code
  return { salaryFrom, salaryTo, currency, gross: null };
}

// Search results are only ever fetched from hh.ru itself — a caller-supplied
// `url` still has to pass this check before the browser will navigate to it,
// so this endpoint can't be turned into an open fetch proxy for arbitrary hosts.
const ALLOWED_SEARCH_HOST = /(^|\.)hh\.ru$/i;

/**
 * EXPERIMENTAL — wired into the Java pipeline only via the explicit
 * "discover from URL" trigger (VacancyPipelineService.discoverFromUrl), never
 * the scheduled/automatic run. RSS discovery (HhApiClient.fetchRss) caps out
 * at 20 results per query with no pagination; this drives a real search on
 * hh.ru like a person typing a query (or pasting a URL they built themselves
 * with hh.ru's own filter UI), which returns ~50 results per page with
 * pagination (dozens of pages for a broad query).
 */
async function searchVacancies({ url: rawUrl, text, area, page: pageNum, schedule, salary }) {
  const context = await getContext();
  const page = await context.newPage();
  try {
    let url;
    if (rawUrl) {
      let parsed;
      try {
        parsed = new URL(rawUrl);
      } catch (e) {
        return { ok: false, reason: 'bad_url' };
      }
      if (!ALLOWED_SEARCH_HOST.test(parsed.hostname)) {
        return { ok: false, reason: 'host_not_allowed' };
      }
      if (pageNum) parsed.searchParams.set('page', pageNum);
      url = parsed.toString();
    } else {
      url = `https://hh.ru/search/vacancy?text=${encodeURIComponent(text)}&area=${encodeURIComponent(area)}&page=${encodeURIComponent(pageNum)}`;
      if (schedule) url += `&schedule=${encodeURIComponent(schedule)}`;
      if (salary) url += `&salary=${encodeURIComponent(salary)}`;
    }

    const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT_MS });
    const status = resp ? resp.status() : 0;
    if (status >= 400) return { ok: false, reason: `http_${status}` };

    // Result cards render in at least two waves — measured on a real search page, the DOM
    // held only ~17 of the eventual 50 cards at the 800ms mark this used to wait, then
    // jumped to the full 50 by ~1.8s and stayed there. A fixed short wait silently returns
    // a partial page instead of failing, so poll until the count stops changing (or give up
    // after a generous cap) rather than trust one magic number.
    await waitForStableCount(page, '[data-qa="vacancy-serp__vacancy"]');

    const items = await page.$$eval('[data-qa="vacancy-serp__vacancy"]', (cards) =>
      cards.map((c) => {
        const idEl = c.querySelector('[id]');
        const titleLink = c.querySelector('[data-qa="serp-item__title"]');
        const titleText = c.querySelector('[data-qa="serp-item__title-text"]');
        const salaryEl = c.querySelector('[data-qa="vacancy-serp__vacancy-compensation"]');
        const employerEl = c.querySelector('[data-qa="vacancy-serp__vacancy-employer-text"]');
        const addrEl = c.querySelector('[data-qa="vacancy-serp__vacancy-address"]');
        // Primary source of the id: the /vacancy/{id} href of the title link —
        // "first element carrying any id attribute" broke silently on layout
        // changes before; keep it only as a fallback.
        const href = titleLink ? titleLink.getAttribute('href') : null;
        const hrefMatch = href ? href.match(/\/vacancy\/(\d+)/) : null;
        return {
          hhId: hrefMatch ? hrefMatch[1] : (idEl ? idEl.id : null),
          title: titleText ? titleText.textContent.trim() : null,
          employerName: employerEl ? employerEl.textContent.trim() : null,
          salaryRawText: salaryEl ? salaryEl.textContent.trim() : null,
          address: addrEl ? addrEl.textContent.trim() : null,
          url: titleLink ? titleLink.getAttribute('href') : null,
        };
      })
    );

    const pagerLabels = await page.locator('[data-qa="pager-page"]').allTextContents().catch(() => []);
    const lastPageLabel = pagerLabels.length ? pagerLabels[pagerLabels.length - 1] : null;

    return {
      ok: true,
      items: items.filter((i) => i.hhId),
      lastPageLabel,
    };
  } catch (e) {
    resetContextIfCrashed(e);
    return { ok: false, reason: `error: ${e.message}` };
  } finally {
    await page.close().catch(() => {});
  }
}

async function scrapeVacancy(hhId) {
  const context = await getContext();
  const page = await context.newPage();
  try {
    const resp = await page.goto(`https://hh.ru/vacancy/${hhId}`, {
      waitUntil: 'domcontentloaded',
      timeout: NAV_TIMEOUT_MS,
      // A person lands on a vacancy from the listing, not out of thin air.
      referer: 'https://hh.ru/search/vacancy',
    });
    const status = resp ? resp.status() : 0;
    if (status === 404) return { ok: false, reason: 'not_found' };
    if (status >= 400) return { ok: false, reason: `http_${status}` };

    // Wait for the client-side app to render the data-qa nodes we read below,
    // but exit as soon as they appear instead of always paying a fixed 800ms
    // (pages missing them entirely — archived etc. — give up after the cap).
    await page
      .waitForSelector('[data-qa="vacancy-salary"], [data-qa="vacancy-experience"]', { timeout: 2000 })
      .catch(() => {});

    const ldJsonRaw = await page
      .locator('script[type="application/ld+json"]')
      .first()
      .textContent()
      .catch(() => null);

    let ld = null;
    if (ldJsonRaw) {
      try {
        ld = JSON.parse(ldJsonRaw);
      } catch (_) {
        ld = null;
      }
    }

    if (!ld || ld['@type'] !== 'JobPosting') {
      // Page loaded but didn't render the expected structure (archived vacancy,
      // unexpected layout, or a bot-challenge page slipping through).
      return { ok: false, reason: 'no_job_posting_data' };
    }

    const textOf = async (selector) =>
      page.locator(`[data-qa="${selector}"]`).first().textContent().catch(() => null);

    // hh.ru renders fields like "Полная занятость · Стажировка" as sibling
    // <span> elements with a CSS-only dot separator, so plain textContent()
    // glues them together with no space at all. Join the spans explicitly.
    const joinedTextOf = async (selector) =>
      page.locator(`[data-qa="${selector}"]`).first()
        .evaluate((el) => Array.from(el.querySelectorAll('span')).map((s) => s.textContent.trim()).filter(Boolean).join(', ') || el.textContent.trim())
        .catch(() => null);

    const salaryRaw = await textOf('vacancy-salary');
    // Structured JSON-LD baseSalary is authoritative when present; the regex
    // parse of the DOM text stays as fallback and as the only source of the
    // gross/net flag ("до вычета"/"на руки"), which JSON-LD doesn't carry.
    const salaryParsed = salaryFromLd(ld) || parseSalary(salaryRaw);
    if (salaryParsed.gross == null) {
      salaryParsed.gross = parseSalary(salaryRaw).gross;
    }

    const keySkills = await page.locator('[data-qa="skills-element"]').allTextContents().catch(() => []);
    const trustedEmployer = (await page.locator('[data-qa="trusted-employer-link"]').count()) > 0;

    const address = ld.jobLocation && ld.jobLocation.address ? ld.jobLocation.address : {};

    return {
      ok: true,
      hhId,
      title: ld.title || (await textOf('vacancy-title')) || '',
      employerName: (ld.hiringOrganization && ld.hiringOrganization.name) || '',
      descriptionHtml: ld.description || '',
      salaryFrom: salaryParsed.salaryFrom,
      salaryTo: salaryParsed.salaryTo,
      currency: salaryParsed.currency,
      salaryGross: salaryParsed.gross,
      salaryRawText: salaryRaw ? salaryRaw.trim() : null,
      city: address.addressLocality || '',
      region: address.addressRegion || '',
      street: address.streetAddress || '',
      experience: (await textOf('vacancy-experience')) || '',
      employment: (await joinedTextOf('common-employment-text')) || '',
      schedule: (await joinedTextOf('work-schedule-by-days-text')) || '',
      keySkills: keySkills.map((s) => s.trim()).filter(Boolean),
      trustedEmployer,
      datePosted: ld.datePosted || null,
      validThrough: ld.validThrough || null,
    };
  } catch (e) {
    resetContextIfCrashed(e);
    return { ok: false, reason: `error: ${e.message}` };
  } finally {
    await page.close().catch(() => {});
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  if (url.pathname === '/scrape') {
    const hhId = url.searchParams.get('hhId');
    if (!hhId || !/^\d+$/.test(hhId)) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, reason: 'bad_hh_id' }));
      return;
    }

    enqueue(() => scrapeVacancy(hhId))
      .then((result) => {
        res.writeHead(result.ok ? 200 : 502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      })
      .catch((e) => {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, reason: `unhandled: ${e.message}` }));
      });
    return;
  }

  if (url.pathname === '/search') {
    const rawUrl = url.searchParams.get('url');
    const text = url.searchParams.get('text');
    if (!rawUrl && !text) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, reason: 'missing_text_or_url' }));
      return;
    }
    const area = url.searchParams.get('area') || '113';
    const pageNum = url.searchParams.get('page') || '0';
    const schedule = url.searchParams.get('schedule') || '';
    const salary = url.searchParams.get('salary') || '';

    enqueue(() => searchVacancies({ url: rawUrl, text, area, page: pageNum, schedule, salary }))
      .then((result) => {
        res.writeHead(result.ok ? 200 : 502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      })
      .catch((e) => {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, reason: `unhandled: ${e.message}` }));
      });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false, reason: 'not_found' }));
});

server.listen(PORT, HOST, () => {
  console.log(`hh-vacancy-scraper listening on http://${HOST}:${PORT} (delay ${MIN_DELAY_MS}-${MIN_DELAY_MS + JITTER_MS}ms, profile ${PROFILE_DIR})`);
});

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
