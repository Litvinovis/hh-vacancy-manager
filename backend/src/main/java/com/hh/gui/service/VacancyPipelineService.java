package com.hh.gui.service;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.HhApiClient;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.client.ScraperClient.ScrapeResult;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.util.DedupKeys;
import com.hh.gui.util.SalaryFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pipeline for a single (person, search) job: RSS-discover new vacancy IDs →
 * scrape full content for pending ones (via the headless-browser sidecar,
 * see ScraperClient) → AI-analyze scraped-but-unanalyzed ones → notify.
 *
 * Each method takes one SearchJob at a time; iterating over all configured
 * jobs is the caller's job (PipelineScheduler for the scheduled run,
 * PipelineController for manual triggers) since different jobs can't be
 * batched together — their AI scoring criteria differ.
 */
@Service
public class VacancyPipelineService {

    private static final Logger log = LoggerFactory.getLogger(VacancyPipelineService.class);

    // Ufa's administrative districts + well-known microdistricts — best-effort text
    // match; the scraped page has no single structured "district" field, only
    // city + street, so we still need to look for these in the free text.
    private static final List<String> DISTRICTS = List.of(
        "Шакша", "Калининский", "Орджоникидзевский", "Кировский", "Ленинский",
        "Октябрьский", "Советский", "Демский");

    private final HhApiClient hhApiClient;
    private final ScraperClient scraperClient;
    private final VacancyAiAnalyzer aiAnalyzer;
    private final VacancyRepository vacancyRepo;
    private final TelegramNotifier telegramNotifier;
    private final RuntimeConfig runtimeConfig;
    private final AiMetrics metrics;

    @Value("${app.pipeline.batch-size:10}")
    private int batchSizeDefault;

    @Value("${app.notifications.enabled:false}")
    private boolean notificationsEnabledDefault;

    public VacancyPipelineService(HhApiClient hhApiClient, ScraperClient scraperClient, VacancyAiAnalyzer aiAnalyzer,
                                   VacancyRepository vacancyRepo, TelegramNotifier telegramNotifier,
                                   RuntimeConfig runtimeConfig, AiMetrics metrics) {
        this.hhApiClient = hhApiClient;
        this.scraperClient = scraperClient;
        this.aiAnalyzer = aiAnalyzer;
        this.vacancyRepo = vacancyRepo;
        this.telegramNotifier = telegramNotifier;
        this.runtimeConfig = runtimeConfig;
        this.metrics = metrics;
        runtimeConfig.setPipelineBatchSize(runtimeConfig.getPipelineBatchSize() > 0 ? runtimeConfig.getPipelineBatchSize() : batchSizeDefault);
        runtimeConfig.setNotificationsEnabled(notificationsEnabledDefault);
    }

    public boolean isNotificationsEnabled() { return runtimeConfig.isNotificationsEnabled(); }
    public void setNotificationsEnabled(boolean enabled) { runtimeConfig.setNotificationsEnabled(enabled); }
    public boolean isAiRateLimited() { return aiAnalyzer.isRateLimited(); }
    public long getAiCooldownUntil() { return aiAnalyzer.getRateLimitCooldownUntil(); }

    private int getBatchSize() {
        return runtimeConfig.getPipelineBatchSize() > 0 ? runtimeConfig.getPipelineBatchSize() : batchSizeDefault;
    }

    /**
     * Full pipeline for one job: discover → scrape → AI-analyze → notify.
     * Manual-trigger entry point — analyzes whatever is pending immediately.
     */
    public PipelineResult runFullPipeline(SearchJob job) {
        return runFullPipeline(job, false);
    }

    /**
     * Same pipeline with the scheduler's batching behavior: with deferSmallAiBatches
     * the AI step lets a small fresh backlog accumulate instead of paying the fixed
     * prompt overhead (profile, rubric, format — ~500 tokens) for a 1-2-vacancy batch
     * on every 10-minute tick (see shouldDeferAnalysis).
     */
    public PipelineResult runFullPipeline(SearchJob job, boolean deferSmallAiBatches) {
        log.info("=== Пайплайн: {} · {} ===", job.personName, job.searchName);

        // URL-only search (sourceUrl set, no RSS queries): the only way to discover
        // anything is the saved URL. Without this, a manual "run" of such a search
        // logged "queries not configured" and silently collected nothing — discovery
        // happened only on the runDueUrlSearches schedule.
        int discovered;
        boolean urlOnly = (job.queries == null || job.queries.isEmpty())
            && job.sourceUrl != null && !job.sourceUrl.isBlank();
        if (urlOnly) {
            discovered = discoverFromUrl(job, job.sourceUrl, MAX_URL_SEARCH_PAGES);
            log.info("Шаг 1 по ссылке ({} · {}): {} новых вакансий", job.personName, job.searchName, discovered);
        } else {
            discovered = discoverNew(job);
            log.info("Шаг 1 ({} · {}): {} новых вакансий", job.personName, job.searchName, discovered);
        }

        int scraped = scrapePending(job);
        log.info("Шаг 2 ({} · {}): скрейпинг обработал {} записей", job.personName, job.searchName, scraped);

        int analyzed;
        if (deferSmallAiBatches && shouldDeferAnalysis(job)) {
            analyzed = 0;
        } else {
            analyzed = analyzePending(job, runtimeConfig.getMaxPerRun());
            log.info("Шаг 3 ({} · {}): {} вакансий проанализировано AI", job.personName, job.searchName, analyzed);
        }

        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(
            job.personName, job.searchName, runtimeConfig.getMinScore(), runtimeConfig.getMaxApproved());
        log.info("Шаг 4 ({} · {}): {} одобренных неуведомлённых", job.personName, job.searchName, approved.size());

        if (!approved.isEmpty()) {
            sendReport(approved, job);
        }

        PipelineResult result = new PipelineResult();
        result.collected = discovered;
        result.newVacancies = discovered;
        result.analyzed = analyzed;
        result.approved = approved.size();
        return result;
    }

    // Scheduler-path batching: don't pay a full prompt's fixed overhead for a couple of
    // fresh rows — they'll be joined by more within the hour (the pipeline ticks every
    // ~10 minutes). Analysis proceeds once the backlog reaches a full AI batch OR the
    // oldest waiting row has waited this long, whichever comes first, so nothing can
    // starve. Manual triggers bypass this entirely.
    private static final long AI_ACCUMULATE_MAX_WAIT_MS = 60L * 60 * 1000;

    private boolean shouldDeferAnalysis(SearchJob job) {
        VacancyRepository.PendingStats stats = vacancyRepo.pendingStats(job.personName, job.searchName);
        if (stats.count() == 0 || stats.count() >= getBatchSize()) return false;
        try {
            Instant oldest = Instant.parse(stats.oldestWaitingSince());
            if (oldest.plusMillis(AI_ACCUMULATE_MAX_WAIT_MS).isBefore(Instant.now())) return false;
        } catch (Exception e) {
            return false; // unparsable timestamp — analyze rather than risk starving the row
        }
        log.info("Шаг 3 ({} · {}): отложен — копим пакет ({} из {} вакансий, старейшая ждёт < часа)",
            job.personName, job.searchName, stats.count(), getBatchSize());
        return true;
    }

    // Safety cap on how many search-result pages a single manual "discover from URL"
    // trigger will walk — each page is a real browser navigation through the sidecar's
    // MIN_DELAY_MS throttle, so an unbounded loop here could turn one click into a
    // multi-minute crawl. Callers can ask for fewer; they can't ask for more.
    private static final int MAX_URL_SEARCH_PAGES = 10;

    /**
     * EXPERIMENTAL, manual-trigger only — discover-then-score a job's candidates
     * from an hh.ru search-results URL the user built themselves (via hh.ru's own
     * filter UI) instead of the job's configured RSS queries. Never called from
     * PipelineScheduler. See ScraperClient.searchByUrl for why: RSS caps at 20
     * results with no pagination, this gets ~50/page with real pagination.
     */
    public PipelineResult runFullPipelineFromUrl(SearchJob job, String url, int maxPages) {
        log.info("=== Пайплайн по ссылке: {} · {} ({}) ===", job.personName, job.searchName, url);

        int discovered = discoverFromUrl(job, url, maxPages);
        log.info("Шаг 1 по ссылке ({} · {}): {} новых вакансий", job.personName, job.searchName, discovered);

        int scraped = scrapePending(job);
        log.info("Шаг 2 ({} · {}): скрейпинг обработал {} записей", job.personName, job.searchName, scraped);

        int analyzed = analyzePending(job, runtimeConfig.getMaxPerRun());
        log.info("Шаг 3 ({} · {}): {} вакансий проанализировано AI", job.personName, job.searchName, analyzed);

        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(
            job.personName, job.searchName, runtimeConfig.getMinScore(), runtimeConfig.getMaxApproved());
        log.info("Шаг 4 ({} · {}): {} одобренных неуведомлённых", job.personName, job.searchName, approved.size());

        if (!approved.isEmpty()) {
            sendReport(approved, job);
        }

        PipelineResult result = new PipelineResult();
        result.collected = discovered;
        result.newVacancies = discovered;
        result.analyzed = analyzed;
        result.approved = approved.size();
        return result;
    }

    /**
     * Walks search-result pages of a caller-supplied hh.ru URL (see
     * ScraperClient.searchByUrl), filters excluded titles, prescreens genuinely new
     * hits (VacancyAiAnalyzer.prescreenHits), and saves each as a scrape-pending stub
     * (or scrape_status='skipped' if prescreen rejected it, so it still counts as
     * "seen"). Always walks every requested page up to MAX_URL_SEARCH_PAGES — no
     * early stop: this listing's known/new hits interleave unpredictably across
     * pages (mass-reposted clones), so a "saturated" page can't predict the next one.
     */
    @Transactional
    protected int discoverFromUrl(SearchJob job, String url, int maxPages) {
        if (isScrapeCoolingDown()) {
            log.warn("Поиск по ссылке ({} · {}) пропущен — скрейпинг заморожен после блокировки", job.personName, job.searchName);
            return 0;
        }
        int pages = Math.min(Math.max(maxPages, 1), MAX_URL_SEARCH_PAGES);
        int saved = 0;

        for (int page = 0; page < pages; page++) {
            ScraperClient.SearchPageResult result = scraperClient.searchByUrl(url, page);
            if (!result.ok()) {
                log.warn("Поиск по ссылке ({} · {}) остановлен на странице {}: {}",
                    job.personName, job.searchName, page, result.reason());
                break;
            }
            if (result.items().isEmpty()) break;

            List<ScraperClient.SearchHit> rawHits = result.items();

            // One IN query per page instead of one exists-lookup per card.
            Set<String> knownHhIds = vacancyRepo.findExistingHhIds(
                rawHits.stream().map(ScraperClient.SearchHit::hhId).toList(), job.personName, job.searchName);
            log.debug("Поиск по ссылке ({} · {}), страница {}: {} карточек, из них уже известных {}",
                job.personName, job.searchName, page, rawHits.size(), knownHhIds.size());

            List<ScraperClient.SearchHit> newHits = filterExcludedHits(rawHits, job.excludeWords).stream()
                .filter(hit -> !knownHhIds.contains(hit.hhId()))
                .toList();
            if (newHits.isEmpty()) continue;

            Map<String, VacancyAiAnalyzer.AiResult> prescreen = aiAnalyzer.prescreenHits(newHits, job).stream()
                .collect(Collectors.toMap(VacancyAiAnalyzer.AiResult::hhId, r -> r, (a, b) -> a));
            long prescreenRejected = prescreen.values().stream().filter(r -> "no".equals(r.verdict())).count();
            log.debug("Поиск по ссылке ({} · {}), страница {}: новых {}, из них отсеяно прескрином {}",
                job.personName, job.searchName, page, newHits.size(), prescreenRejected);

            for (ScraperClient.SearchHit hit : newHits) {
                VacancyAiAnalyzer.AiResult verdict = prescreen.get(hit.hhId());
                boolean passed = verdict == null || !"no".equals(verdict.verdict());

                Vacancy v = new Vacancy();
                v.setHhId(hit.hhId());
                v.setTitle(hit.title());
                v.setCompany(hit.employerName());
                v.setUrl(hit.url());
                v.setStatus("new");
                v.setCreatedAt(Instant.now().toString());
                v.setSource("hh");
                v.setSourceQuery(job.searchName);
                v.setPerson(job.personName);
                v.setSearchName(job.searchName);
                v.setUserId(job.isGlobal ? null : job.userId);
                v.setSearchId(job.searchId);
                v.setRemote(job.isRemote());
                v.setDedupKey(DedupKeys.compute(hit.title(), hit.employerName()));

                if (passed) {
                    v.setAiVerdict("pending");
                    v.setAiScore(0);
                    v.setScrapeStatus("pending");
                } else {
                    v.setAiVerdict("no");
                    v.setAiScore(0);
                    v.setAiReason("Прескрининг: " + verdict.reason());
                    v.setScrapeStatus("skipped");
                }

                try {
                    vacancyRepo.save(v);
                    saved++;
                } catch (Exception e) {
                    log.warn("Не удалось сохранить {} ({} · {}): {}", hit.hhId(), job.personName, job.searchName, e.getMessage());
                }
            }
        }
        return saved;
    }

    private List<ScraperClient.SearchHit> filterExcludedHits(List<ScraperClient.SearchHit> hits, List<String> excludeWords) {
        if (excludeWords == null || excludeWords.isEmpty()) return hits;
        List<String> lower = excludeWords.stream().map(String::toLowerCase).toList();
        List<ScraperClient.SearchHit> result = new ArrayList<>();
        for (ScraperClient.SearchHit h : hits) {
            String title = h.title() != null ? h.title().toLowerCase() : "";
            if (lower.stream().noneMatch(title::contains)) result.add(h);
        }
        return result;
    }

    /**
     * RSS-discover new hh_ids for this job's queries, drop obviously-excluded
     * titles before ever scraping them, and save the rest as scrape-pending stubs.
     *
     * Genuinely new hits go through the same cheap AI prescreen the URL-discovery
     * path uses (title-only here — RSS carries no employer/salary/address) so a
     * title the exclude-words filter can't catch still skips the full browser
     * scrape + real AI analysis. Fails OPEN like the URL path: any prescreen
     * problem means everything passes through unfiltered.
     */
    @Transactional
    protected int discoverNew(SearchJob job) {
        if (job.queries == null || job.queries.isEmpty()) {
            log.warn("Поисковые запросы не настроены для {} · {}", job.personName, job.searchName);
            return 0;
        }

        Map<String, Vacancy> seen = new LinkedHashMap<>();
        for (String query : job.queries) {
            for (Vacancy v : hhApiClient.fetchRss(query, job.area, job.schedule, job.salaryMin)) {
                seen.putIfAbsent(v.getHhId(), v);
            }
        }

        List<Vacancy> filtered = filterExcluded(new ArrayList<>(seen.values()), job.excludeWords);
        Set<String> knownHhIds = vacancyRepo.findExistingHhIds(
            filtered.stream().map(Vacancy::getHhId).toList(), job.personName, job.searchName);
        List<Vacancy> fresh = filtered.stream().filter(v -> !knownHhIds.contains(v.getHhId())).toList();
        if (fresh.isEmpty()) return 0;

        Map<String, VacancyAiAnalyzer.AiResult> prescreen = aiAnalyzer.prescreenHits(
            fresh.stream()
                .map(v -> new ScraperClient.SearchHit(v.getHhId(), v.getTitle(), null, null, null, null, v.getUrl()))
                .toList(), job).stream()
            .collect(Collectors.toMap(VacancyAiAnalyzer.AiResult::hhId, r -> r, (a, b) -> a));

        int saved = 0;
        for (Vacancy v : fresh) {
            VacancyAiAnalyzer.AiResult verdict = prescreen.get(v.getHhId());
            boolean passed = verdict == null || !"no".equals(verdict.verdict());
            v.setPerson(job.personName);
            v.setSearchName(job.searchName);
            v.setUserId(job.isGlobal ? null : job.userId);
            v.setSearchId(job.searchId);
            v.setRemote(job.isRemote());
            v.setSourceQuery(job.searchName);
            if (passed) {
                v.setScrapeStatus("pending");
            } else {
                // Saved anyway so it counts as "already seen" on future runs — just
                // never scraped or fully analyzed (mirrors discoverFromUrl).
                v.setAiVerdict("no");
                v.setAiScore(0);
                v.setAiReason("Прескрининг: " + verdict.reason());
                v.setScrapeStatus("skipped");
            }
            try {
                vacancyRepo.save(v);
                saved++;
            } catch (Exception e) {
                log.warn("Не удалось сохранить {} ({} · {}): {}", v.getHhId(), job.personName, job.searchName, e.getMessage());
            }
        }
        return saved;
    }

    /** Drop candidates whose title contains an excluded word — before scraping, not just before AI. */
    private List<Vacancy> filterExcluded(List<Vacancy> vacancies, List<String> excludeWords) {
        if (excludeWords == null || excludeWords.isEmpty()) return vacancies;
        List<String> lower = excludeWords.stream().map(String::toLowerCase).toList();
        List<Vacancy> result = new ArrayList<>();
        for (Vacancy v : vacancies) {
            String title = v.getTitle() != null ? v.getTitle().toLowerCase() : "";
            if (lower.stream().noneMatch(title::contains)) result.add(v);
        }
        return result;
    }

    // Failure reasons about just THAT one vacancy, not the rest of the batch: "not_found"/
    // "archived" (posting gone), "no_job_posting_data" (page didn't render as expected),
    // "http_403" (hh.ru's per-vacancy access restriction — verified live: some hh_ids 403
    // consistently while neighbours in the same session scraped fine). A DDoS-Guard session
    // block is reported separately as "blocked" (site-wide). Everything else is site-wide —
    // the next attempt is just as likely to fail. Backstop: too many 403s in one run still
    // bails out (guards against a rate-limit that doesn't carry the DDoS-Guard signature).
    private static final Set<String> PER_VACANCY_FAILURE_REASONS = Set.of("not_found", "no_job_posting_data", "http_403", "archived");
    private static final int MAX_HTTP_403_PER_RUN = 8;
    private static final int MAX_CONSECUTIVE_SCRAPE_FAILURES = 3;
    // Per-vacancy failed attempts (page loads with no JobPosting data) before a row
    // stops being re-queued — without a cap, permanently broken rows sat at the front
    // of the created_at-ordered scrape queue and ate scraper time on every run.
    private static final int MAX_SCRAPE_ATTEMPTS = 5;

    // After a site-wide bail-out (hh.ru blocking / sidecar down), freeze ALL scraping
    // for a while instead of hammering again on the very next 10-minute run — from an
    // anti-bot's perspective, retrying a blocked session on a fixed short interval is
    // exactly what a bot does. Backoff doubles per consecutive bail-out, capped.
    private static final long SCRAPE_COOLDOWN_BASE_MS = 30L * 60 * 1000;
    private static final long SCRAPE_COOLDOWN_MAX_MS = 4L * 60 * 60 * 1000;
    private volatile long scrapeCooldownUntil = 0;
    private int scrapeCooldownStrikes = 0;

    private static boolean isSiteWideFailure(String reason) {
        return reason != null && !PER_VACANCY_FAILURE_REASONS.contains(reason);
    }

    public boolean isScrapeCoolingDown() {
        return System.currentTimeMillis() < scrapeCooldownUntil;
    }

    public long getScrapeCooldownUntil() {
        return scrapeCooldownUntil;
    }

    private synchronized void enterScrapeCooldown() {
        scrapeCooldownStrikes++;
        long cooldown = Math.min(SCRAPE_COOLDOWN_BASE_MS << (scrapeCooldownStrikes - 1), SCRAPE_COOLDOWN_MAX_MS);
        scrapeCooldownUntil = System.currentTimeMillis() + cooldown;
        log.warn("Скрейпинг заморожен на {} мин (подряд блокировок: {})", cooldown / 60000, scrapeCooldownStrikes);
    }

    private synchronized void onScrapeSuccess() {
        scrapeCooldownStrikes = 0;
    }

    /**
     * Scrape full content for rows still pending (or previously failed) for this job.
     * Reuses already-scraped content for the same hh_id if a different (person,
     * search) already fetched it, instead of hitting the scraper sidecar again.
     *
     * Bails out early after several consecutive site-wide failures (see isSiteWideFailure)
     * instead of grinding through the rest of the batch — each scrape can block for up to
     * the configured HTTP read timeout, so a genuinely down/hung sidecar, or hh.ru itself
     * blocking/rate-limiting the scraping session, could otherwise stall this step for
     * maxPerRun × timeout (worst case, well over an hour), failing every single attempt.
     * Unscraped rows are simply left 'pending' and picked up on the next run.
     */
    private int scrapePending(SearchJob job) {
        if (isScrapeCoolingDown()) {
            log.info("Скрейпинг ({} · {}) пропущен — заморожен после блокировки ещё {} мин",
                job.personName, job.searchName, Math.max(0, (scrapeCooldownUntil - System.currentTimeMillis()) / 60000));
            return 0;
        }
        int count = 0;
        int consecutiveFailures = 0;
        int http403InRun = 0;
        List<Vacancy> pending = vacancyRepo.findScrapePending(job.personName, job.searchName,
            runtimeConfig.getMaxPerRun(), MAX_SCRAPE_ATTEMPTS);
        for (Vacancy v : pending) {
            // The cooldown may have been engaged by a PARALLEL run (scheduler vs manual
            // trigger) after this loop already started — the entry check above won't
            // catch that, and this thread would keep hammering a blocked session.
            if (isScrapeCoolingDown()) {
                log.info("Скрейпинг ({} · {}) прерван — другой запуск словил блокировку, осталось {} вакансий",
                    job.personName, job.searchName, pending.size() - count);
                break;
            }
            Optional<Vacancy> existing = vacancyRepo.findFirstScrapedByHhId(v.getHhId());
            if (existing.isEmpty()) {
                // Cross-city fallback — same real posting, different hh_id per city listing.
                existing = vacancyRepo.findFirstScrapedByDedupKey(v.getDedupKey());
            }
            if (existing.isPresent() && !existing.get().getId().equals(v.getId())) {
                copyScraped(existing.get(), v);
                vacancyRepo.updateScraped(v);
                count++;
                consecutiveFailures = 0;
                continue;
            }

            ScrapeResult r = scraperClient.scrape(v.getHhId());
            if (r.ok()) {
                applyScrapeResult(v, r);
                v.setScrapeStatus("ok");
                consecutiveFailures = 0;
                onScrapeSuccess();
            } else {
                // archived is terminal like not_found — the posting exists but is closed;
                // retrying the scrape will never make it analyzable.
                v.setScrapeStatus("not_found".equals(r.reason()) || "archived".equals(r.reason()) ? "not_found" : "failed");
                log.warn("Скрейпинг {} ({} · {}) не удался: {}", v.getHhId(), job.personName, job.searchName, r.reason());
                if (isSiteWideFailure(r.reason())) {
                    consecutiveFailures++;
                } else {
                    consecutiveFailures = 0;
                    // Uses up this row's own retry budget only for failures that are
                    // about THIS page (see findScrapePending) — a blocked session or
                    // a downed sidecar shouldn't burn any vacancy's attempts.
                    if (!"not_found".equals(r.reason())) {
                        vacancyRepo.incrementScrapeAttempts(v.getId());
                    }
                }
                // Backstop (see PER_VACANCY_FAILURE_REASONS): individually a 403 is that
                // one posting restricted, but a pile of them in one run smells like a
                // rate-limit the sidecar couldn't attribute to DDoS-Guard.
                if ("http_403".equals(r.reason()) && ++http403InRun >= MAX_HTTP_403_PER_RUN) {
                    vacancyRepo.updateScraped(v);
                    count++;
                    enterScrapeCooldown();
                    log.warn("Скрейпинг ({} · {}) остановлен: {} http_403 за один прогон — похоже на rate-limit, оставшиеся {} вакансий останутся в очереди",
                        job.personName, job.searchName, http403InRun, pending.size() - count);
                    break;
                }
            }
            vacancyRepo.updateScraped(v);
            count++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_SCRAPE_FAILURES) {
                enterScrapeCooldown();
                log.warn("Скрейпинг ({} · {}) остановлен после {} подряд ошибок — сайдкар недоступен или hh.ru блокирует запросы, оставшиеся {} вакансий останутся в очереди",
                    job.personName, job.searchName, consecutiveFailures, pending.size() - count);
                break;
            }
        }
        return count;
    }

    // Freshness re-check pacing (see checkVacancyFreshness): a 7-day cadence over the
    // ~3.6k 'yes' postings needs ~520 checks/day; 5 per 10-minute scheduler tick caps
    // at ~720/day — enough headroom, spread perfectly evenly, and each page load still
    // goes through the sidecar's human-paced queue. No bursts an anti-bot could latch onto.
    static final int FRESHNESS_RECHECK_DAYS = 7;
    public static final int FRESHNESS_BATCH_PER_TICK = 5;
    // "Yield to new content" used to mean "run only when the scrape queue is EXACTLY
    // empty" — but with continuous discovery, nightly legacy imports and failed-row
    // retries the queue almost never hits zero, and the freshness pass starved for
    // days. A small remainder is fine to share the tick with: the sidecar clears it
    // in minutes, and new rows still get scraped first within scrapePending itself.
    static final int FRESHNESS_MAX_SCRAPE_BACKLOG = 10;

    /**
     * Re-verifies that approved ('yes') postings are still live on hh.ru — they get
     * archived and deleted all the time, and a dead posting in the UI or a Telegram
     * report wastes the reader's attention. Each posting is re-checked at most once
     * per FRESHNESS_RECHECK_DAYS, oldest-confirmation first (expired valid_through
     * jumps the queue — see findDueFreshnessCheck).
     *
     * Deliberately the lowest-priority scraper client: skips entirely while any NEW
     * vacancy still waits for its first scrape, or while the scrape cooldown is
     * active, so it only ever consumes idle capacity.
     */
    public FreshnessResult checkVacancyFreshness(int limit) {
        FreshnessResult result = new FreshnessResult();
        if (isScrapeCoolingDown()) return result;
        if (vacancyRepo.countUnscrapedNew() > FRESHNESS_MAX_SCRAPE_BACKLOG) return result;

        List<Vacancy> due = vacancyRepo.findDueFreshnessCheck(FRESHNESS_RECHECK_DAYS, limit);
        for (Vacancy v : due) {
            if (isScrapeCoolingDown()) break; // a parallel run may have hit a block mid-loop
            ScrapeResult r = scraperClient.scrape(v.getHhId());
            if (r.ok()) {
                // Alive — refresh the content too: salary/description edits are common.
                applyScrapeResult(v, r);
                v.setScrapeStatus("ok");
                vacancyRepo.updateScraped(v);
                vacancyRepo.markFreshnessChecked(v.getId());
                result.alive++;
                onScrapeSuccess();
            } else if ("archived".equals(r.reason()) || "not_found".equals(r.reason())) {
                vacancyRepo.markClosed(v.getId());
                result.closed++;
                log.info("Актуализация: вакансия {} ({} · {}) снята с hh.ru ({}) — скрыта",
                    v.getHhId(), v.getPerson(), v.getSearchName(), r.reason());
            } else if (isSiteWideFailure(r.reason())) {
                // Same signal scrapePending freezes on — don't grind a blocked session
                // for the sake of a background chore; the rows stay due for later.
                log.warn("Актуализация остановлена: {} — оставшиеся проверки подождут", r.reason());
                break;
            } else {
                // Per-vacancy hiccup (403/render glitch) — inconclusive, not proof of
                // death: stamp the check so this row waits its full interval again
                // instead of being retried every tick.
                vacancyRepo.markFreshnessChecked(v.getId());
                result.inconclusive++;
            }
        }
        if (result.alive + result.closed + result.inconclusive > 0) {
            log.info("Актуализация вакансий: живых {}, закрытых {}, неясных {}",
                result.alive, result.closed, result.inconclusive);
        }
        return result;
    }

    public static class FreshnessResult {
        public int alive;
        public int closed;
        public int inconclusive;
    }

    private void copyScraped(Vacancy from, Vacancy to) {
        to.setTitle(from.getTitle());
        to.setCompany(from.getCompany());
        to.setEmployerName(from.getEmployerName());
        to.setDescription(from.getDescription());
        to.setSalaryFrom(from.getSalaryFrom());
        to.setSalaryTo(from.getSalaryTo());
        to.setCurrency(from.getCurrency());
        to.setSalaryGross(from.isSalaryGross());
        to.setAddress(from.getAddress());
        to.setDistrict(from.getDistrict());
        to.setExperience(from.getExperience());
        to.setEmployment(from.getEmployment());
        to.setKeySkills(from.getKeySkills());
        to.setTrustedEmployer(from.isTrustedEmployer());
        to.setValidThrough(from.getValidThrough());
        if (to.getPublishedAt() == null || to.getPublishedAt().isBlank()) {
            to.setPublishedAt(from.getPublishedAt());
        }
        to.setDedupKey(DedupKeys.compute(from.getTitle(), from.getEmployerName()));
        to.setScrapeStatus("ok");
    }

    private void applyScrapeResult(Vacancy v, ScrapeResult r) {
        String descriptionText = htmlToText(r.descriptionHtml());
        if (r.title() != null && !r.title().isBlank()) v.setTitle(r.title());
        v.setCompany(r.employerName());
        v.setEmployerName(r.employerName());
        v.setDescription(descriptionText);
        if (r.salaryFrom() != null) v.setSalaryFrom(r.salaryFrom());
        if (r.salaryTo() != null) v.setSalaryTo(r.salaryTo());
        if (r.currency() != null) v.setCurrency(r.currency());
        v.setSalaryGross(Boolean.TRUE.equals(r.salaryGross()));
        v.setAddress(String.join(", ", nonBlank(r.city(), r.street())));
        v.setDistrict(extractDistrict(String.join(" ", nonBlank(r.city(), r.street(), descriptionText))));
        v.setExperience(r.experience());
        v.setEmployment(r.employment());
        v.setKeySkills(r.keySkills() != null ? String.join(", ", r.keySkills()) : "");
        v.setTrustedEmployer(r.trustedEmployer());
        v.setValidThrough(r.validThrough());
        // JSON-LD datePosted (ISO) is authoritative — URL-discovered rows have no
        // publish date at all otherwise, and findPending orders by published_at.
        if (r.datePosted() != null && !r.datePosted().isBlank()) {
            v.setPublishedAt(r.datePosted());
        }
        // RSS-discovered rows carry a title only at save time, so their dedup key can
        // only be built here, once the scrape reveals the employer — without this the
        // cross-city clone reuse below never fires for the RSS path at all (measured
        // live: 6.7k of 7.6k rows had no key).
        v.setDedupKey(DedupKeys.compute(v.getTitle(), v.getEmployerName()));
    }

    private static List<String> nonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    private String extractDistrict(String text) {
        if (text == null) return "";
        for (String d : DISTRICTS) {
            if (text.contains(d)) return d;
        }
        return "";
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        String withBreaks = html
            .replaceAll("(?i)</li>", "\n")
            .replaceAll("(?i)</p>", "\n\n")
            .replaceAll("(?i)<li>", "• ")
            .replaceAll("(?i)<br\\s*/?>", "\n");
        String stripped = withBreaks.replaceAll("<[^>]+>", "");
        // Full entity decoding (named and numeric) — the previous hand-picked list of
        // five entities left &mdash;/&quot;/&#8212;-style leftovers in AI prompts.
        String unescaped = org.jsoup.parser.Parser.unescapeEntities(stripped, false);
        return unescaped
            .replace('\u00A0', ' ') // &nbsp; decodes to a non-breaking space
            .replaceAll("[ \\t]{2,}", " ")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
    }

    /** AI-analyze scraped-but-unanalyzed vacancies for this job, up to maxPerRun. */
    private int analyzePending(SearchJob job, int maxPerRun) {
        int totalAnalyzed = 0;
        int processed = 0;
        while (processed < maxPerRun) {
            int batchSize = Math.min(getBatchSize(), maxPerRun - processed);
            List<Vacancy> batch = vacancyRepo.findPending(job.personName, job.searchName, batchSize);
            if (batch.isEmpty()) break;

            totalAnalyzed += analyzeBatchWithDedup(batch, job);
            processed += batch.size();
        }
        return totalAnalyzed;
    }

    /** Analyze ALL scraped-but-pending vacancies for this job (no cap). */
    public int analyzeAllPending(SearchJob job) {
        int totalAnalyzed = 0;
        int batchNum = 0;
        while (true) {
            if (aiAnalyzer.isRateLimited()) {
                log.info("analyzeAllPending остановлен ({} · {}) — cooldown после {} пакетов",
                    job.personName, job.searchName, batchNum);
                break;
            }
            List<Vacancy> batch = vacancyRepo.findPending(job.personName, job.searchName, getBatchSize());
            if (batch.isEmpty()) break;

            int batchAnalyzed = analyzeBatchWithDedup(batch, job);
            if (batchAnalyzed == 0) {
                // Zero progress on a non-empty batch (provider down, or every response
                // unusable) — findPending would return the exact same rows again, so
                // looping on would just re-send the same batch until rate-limited.
                log.warn("analyzeAllPending остановлен ({} · {}) — пакет из {} вакансий не дал прогресса",
                    job.personName, job.searchName, batch.size());
                break;
            }
            totalAnalyzed += batchAnalyzed;
            batchNum++;
        }
        return totalAnalyzed;
    }

    /**
     * Stamps this job's criteria hash on every vacancy in the batch, copies a
     * verdict from any other (user, search) that already scored the exact same
     * real vacancy under scoring-equivalent criteria (mirrors the scrape-reuse
     * pattern in scrapePending, one layer up — see findAnalyzedByHhIdAndCriteriaHash),
     * and only sends the genuine misses to the real AI call.
     */
    // How many times a still-'pending' row may be sent to the LLM (and silently
    // omitted from its answer) before it's marked 'error' instead of being re-sent
    // in every future run — see VacancyRepository.markAiExhausted.
    private static final int MAX_AI_ATTEMPTS = 3;

    private int analyzeBatchWithDedup(List<Vacancy> batch, SearchJob job) {
        String criteriaHash = aiAnalyzer.computeCriteriaHash(job);
        // Every vacancy in this batch gets the same hash (it's a property of the job, not
        // the vacancy) — one batched UPDATE instead of one round-trip per vacancy.
        vacancyRepo.updateCriteriaHashBatch(batch.stream().map(Vacancy::getId).toList(), criteriaHash);

        List<Vacancy> needsAi = new ArrayList<>();
        int deduped = 0;
        int autoRejected = 0;

        for (Vacancy v : batch) {
            // Deterministic zero-token reject: an explicit salary ceiling below the
            // job's floor can't become a "yes" no matter what the description says.
            if (isBelowSalaryFloor(v, job)) {
                vacancyRepo.updateAiResult(v.getHhId(), job.personName, job.searchName, 0, "no",
                    "Зарплата до " + v.getSalaryTo() + "₽ ниже минимума " + job.salaryMin + "₽");
                autoRejected++;
                continue;
            }
            Optional<Vacancy> match = vacancyRepo.findAnalyzedByHhIdAndCriteriaHash(v.getHhId(), criteriaHash);
            if (match.isEmpty()) {
                // Cross-city fallback — same real posting, different hh_id per city listing.
                match = vacancyRepo.findAnalyzedByDedupKeyAndCriteriaHash(v.getDedupKey(), criteriaHash);
            }
            if (match.isPresent()) {
                Vacancy m = match.get();
                vacancyRepo.updateAiResult(v.getHhId(), job.personName, job.searchName,
                    m.getAiScore() != null ? m.getAiScore() : 0, m.getAiVerdict(), m.getAiReason());
                deduped++;
            } else {
                needsAi.add(v);
            }
        }
        if (autoRejected > 0) {
            log.info("Зарплатный фильтр ({} · {}): {} вакансий отклонено без AI-вызова", job.personName, job.searchName, autoRejected);
        }
        if (deduped > 0) {
            log.info("AI-дедуп ({} · {}): {} вакансий переиспользовано без вызова AI", job.personName, job.searchName, deduped);
            metrics.recordVacanciesDeduped(deduped);
        }

        // Clone collapsing within the batch itself: the DB lookups above only reuse
        // verdicts that ALREADY exist, so a batch containing N copies of the same real
        // posting (same dedup_key, different hh_id per city) still sent all N to the
        // LLM. Send one representative per clone group; fan its verdict out to the rest.
        Map<String, List<Vacancy>> cloneGroups = new LinkedHashMap<>();
        List<Vacancy> representatives = new ArrayList<>();
        for (Vacancy v : needsAi) {
            String key = v.getDedupKey();
            if (key == null || key.isEmpty()) {
                representatives.add(v); // no key — never collapse, judge individually
                continue;
            }
            List<Vacancy> group = cloneGroups.computeIfAbsent(key, k -> new ArrayList<>());
            if (group.isEmpty()) representatives.add(v);
            group.add(v);
        }
        if (representatives.size() < needsAi.size()) {
            log.info("AI-анализ ({} · {}): {} вакансий схлопнуто в {} уникальных по dedup_key внутри пакета",
                job.personName, job.searchName, needsAi.size(), representatives.size());
        }

        int aiAnalyzed = 0;
        if (!representatives.isEmpty()) {
            Map<String, String> keyByHhId = new HashMap<>();
            for (Vacancy v : representatives) {
                if (v.getDedupKey() != null && !v.getDedupKey().isEmpty()) keyByHhId.put(v.getHhId(), v.getDedupKey());
            }
            List<VacancyAiAnalyzer.AiResult> results = aiAnalyzer.analyzeBatch(representatives, job);
            Set<String> returnedIds = new HashSet<>();
            for (var r : results) {
                vacancyRepo.updateAiResult(r.hhId(), job.personName, job.searchName, r.score(), r.verdict(), r.reason());
                returnedIds.add(r.hhId());
                aiAnalyzed++;
                // Fan the verdict out to this representative's clone group members.
                List<Vacancy> group = cloneGroups.getOrDefault(keyByHhId.get(r.hhId()), List.of());
                for (Vacancy member : group) {
                    if (member.getHhId().equals(r.hhId())) continue;
                    vacancyRepo.updateAiResult(member.getHhId(), job.personName, job.searchName,
                        r.score(), r.verdict(), r.reason());
                    deduped++;
                    metrics.recordVacanciesDeduped(1);
                }
            }
            metrics.recordVacanciesAnalyzed(aiAnalyzed);

            // The model DID answer but silently omitted some rows — count the wasted
            // round-trip against them, and give up on rows that keep being omitted.
            // An empty result (provider down/cooldown) deliberately doesn't count:
            // it says nothing about these particular vacancies. Clone-group members of
            // an omitted representative stay pending untouched — one of them simply
            // becomes the representative on a later run.
            if (!results.isEmpty()) {
                List<Long> omitted = representatives.stream()
                    .filter(v -> !returnedIds.contains(v.getHhId()))
                    .map(Vacancy::getId)
                    .toList();
                if (!omitted.isEmpty()) {
                    vacancyRepo.incrementAiAttemptsBatch(omitted);
                    int exhausted = vacancyRepo.markAiExhausted(job.personName, job.searchName, MAX_AI_ATTEMPTS);
                    if (exhausted > 0) {
                        log.warn("AI-анализ ({} · {}): {} вакансий помечено 'error' — модель стабильно пропускает их в ответе",
                            job.personName, job.searchName, exhausted);
                    }
                }
            }
        }
        return autoRejected + deduped + aiAnalyzed;
    }

    private static boolean isBelowSalaryFloor(Vacancy v, SearchJob job) {
        if (job.salaryMin <= 0) return false;
        if (v.getSalaryTo() == null || v.getSalaryTo() <= 0) return false;
        String currency = v.getCurrency();
        // Only rubles are comparable to the configured floor; anything else goes to AI.
        if (currency != null && !currency.isBlank()
            && !"RUR".equalsIgnoreCase(currency) && !"RUB".equalsIgnoreCase(currency)) return false;
        return v.getSalaryTo() < job.salaryMin;
    }

    /**
     * Re-analyze eligible vacancies for this job (ai_verdict not in 'no'/'fraud',
     * status != 'rejected'): reset to pending, re-run AI, send a report.
     */
    @Transactional
    public ReanalyzeResult reanalyzeJob(SearchJob job) {
        int resetCount = vacancyRepo.resetAiForRescan(job.personName, job.searchName);
        log.info("Переоценка ({} · {}): сброшено {}", job.personName, job.searchName, resetCount);

        ReanalyzeResult result = new ReanalyzeResult();
        if (resetCount == 0) {
            return result;
        }

        result.reset = resetCount;
        result.analyzed = analyzeAllPending(job);

        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(
            job.personName, job.searchName, runtimeConfig.getMinScore(), runtimeConfig.getMaxApproved());
        if (!approved.isEmpty()) {
            sendReport(approved, job);
        }
        result.approved = approved.size();
        return result;
    }

    // Telegram's sendMessage hard-caps text at 4096 chars; stay under that with margin.
    // A single unbounded-size report (maxApproved goes up to 50 in settings) can easily
    // exceed it, and a rejected too-long message previously meant NONE of the batch got
    // marked notified — the same (now even larger) batch would be rebuilt and rejected
    // again on every future run, forever. Chunking into multiple messages and marking
    // each chunk's vacancies notified independently avoids that stuck state.
    private static final int TELEGRAM_MAX_MESSAGE_CHARS = 4000;

    private void sendReport(List<Vacancy> approved, SearchJob job) {
        if (!runtimeConfig.isNotificationsEnabled()) {
            log.info("Уведомления отключены — отчёт ({} · {}, {} одобренных) не отправлен",
                job.personName, job.searchName, approved.size());
            return;
        }

        String header = "🔍 <b>" + escapeHtml(job.personName) + " · " + escapeHtml(job.searchName) + "</b>\n\n";
        List<List<Vacancy>> chunks = chunkReport(approved, header);

        int notifiedCount = 0;
        for (List<Vacancy> chunk : chunks) {
            String message = formatReport(chunk, header);
            if (telegramNotifier.send(message)) {
                vacancyRepo.markNotified(chunk.stream().map(Vacancy::getId).collect(Collectors.toList()));
                notifiedCount += chunk.size();
            } else {
                log.warn("Не удалось отправить часть отчёта ({} · {}, {} вакансий) — останутся неуведомлёнными",
                    job.personName, job.searchName, chunk.size());
            }
        }
        log.info("Отчёт отправлен ({} · {}, {}/{} вакансий, {} сообщени{})",
            job.personName, job.searchName, notifiedCount, approved.size(), chunks.size(),
            chunks.size() == 1 ? "е" : "я");
    }

    /** Splits vacancies into groups that each fit under TELEGRAM_MAX_MESSAGE_CHARS once formatted. */
    private List<List<Vacancy>> chunkReport(List<Vacancy> vacancies, String header) {
        List<List<Vacancy>> chunks = new ArrayList<>();
        List<Vacancy> current = new ArrayList<>();
        int currentLen = header.length();

        for (Vacancy v : vacancies) {
            int entryLen = formatVacancyEntry(v).length();
            if (!current.isEmpty() && currentLen + entryLen > TELEGRAM_MAX_MESSAGE_CHARS) {
                chunks.add(current);
                current = new ArrayList<>();
                currentLen = header.length();
            }
            current.add(v);
            currentLen += entryLen;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    private String formatReport(List<Vacancy> vacancies, String header) {
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < vacancies.size(); i++) {
            sb.append(formatVacancyEntry(vacancies.get(i)));
        }
        return sb.toString();
    }

    private String formatVacancyEntry(Vacancy v) {
        int score = v.getAiScore() != null ? v.getAiScore() : 0;
        String emoji = score >= 80 ? "🟢" : score >= 60 ? "🟡" : "🟠";
        String salary = SalaryFormatter.forReport(v);
        String company = v.getCompany() != null && !v.getCompany().isEmpty() ? escapeHtml(v.getCompany()) : "компания не указана";
        // Title/reason are scraped/AI-generated text with no hard length cap upstream —
        // truncate defensively so one unusually long entry can't alone blow past Telegram's
        // 4096-char message limit regardless of how chunkReport groups entries.
        String title = truncate(v.getTitle(), 150);
        String reason = truncate(v.getAiReason(), 300);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>[%d%%]</b> %s\n", emoji, score, escapeHtml(title)));
        sb.append(String.format("   🏢 %s | 💰 %s\n", company, salary));
        sb.append(String.format("   💡 %s\n", escapeHtml(reason)));
        sb.append(String.format("   🔗 %s\n\n", v.getUrl()));
        return sb.toString();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static class PipelineResult {
        public int collected;
        public int newVacancies;
        public int analyzed;
        public int approved;
    }

    public static class ReanalyzeResult {
        public int reset;
        public int analyzed;
        public int approved;
    }
}
