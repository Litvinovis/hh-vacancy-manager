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
     */
    public PipelineResult runFullPipeline(SearchJob job) {
        log.info("=== Пайплайн: {} · {} ===", job.personName, job.searchName);

        int discovered = discoverNew(job);
        log.info("Шаг 1 ({} · {}): {} новых вакансий", job.personName, job.searchName, discovered);

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
     * Walks search-result pages of a caller-supplied hh.ru URL (via the scraper
     * sidecar's browser session, see ScraperClient.searchByUrl), filters out excluded
     * titles the same way discoverNew does, then for genuinely new hits:
     *   1. cheap AI prescreen on the card alone (title/employer/salary/address — see
     *      VacancyAiAnalyzer.prescreenHits) to decide whether it's worth a full scrape;
     *   2. hits that pass are saved as scrape-pending stubs (reusing scraped content/AI
     *      verdict from another city's posting of the same real vacancy, if dedup_key
     *      matches — see computeDedupKey);
     *   3. hits that don't pass are still saved, just marked scrape_status='skipped' so
     *      they count as "already seen" on future runs instead of being re-prescreened
     *      forever.
     * Stops walking pages early once EARLY_STOP threshold consecutive already-known hits
     * are seen — hh.ru lists newest-first, so a run of already-known hits means the rest
     * of the listing was already covered by a previous run.
     */
    @Transactional
    protected int discoverFromUrl(SearchJob job, String url, int maxPages) {
        int pages = Math.min(Math.max(maxPages, 1), MAX_URL_SEARCH_PAGES);
        int earlyStopThreshold = Math.max(1, runtimeConfig.getUrlSearchEarlyStopThreshold());
        int saved = 0;
        int consecutiveSeen = 0;

        pageLoop:
        for (int page = 0; page < pages; page++) {
            ScraperClient.SearchPageResult result = scraperClient.searchByUrl(url, page);
            if (!result.ok()) {
                log.warn("Поиск по ссылке ({} · {}) остановлен на странице {}: {}",
                    job.personName, job.searchName, page, result.reason());
                break;
            }
            if (result.items().isEmpty()) break;

            List<ScraperClient.SearchHit> newHits = new ArrayList<>();
            for (ScraperClient.SearchHit hit : filterExcludedHits(result.items(), job.excludeWords)) {
                if (vacancyRepo.existsByHhIdPersonSearch(hit.hhId(), job.personName, job.searchName)) {
                    consecutiveSeen++;
                    if (consecutiveSeen >= earlyStopThreshold) {
                        log.info("Поиск по ссылке ({} · {}) остановлен: {} подряд уже известных вакансий — дальше только старые",
                            job.personName, job.searchName, consecutiveSeen);
                        break pageLoop;
                    }
                    continue;
                }
                consecutiveSeen = 0;
                newHits.add(hit);
            }
            if (newHits.isEmpty()) continue;

            Map<String, VacancyAiAnalyzer.AiResult> prescreen = aiAnalyzer.prescreenHits(newHits, job).stream()
                .collect(Collectors.toMap(VacancyAiAnalyzer.AiResult::hhId, r -> r, (a, b) -> a));

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
                v.setDedupKey(computeDedupKey(hit.title(), hit.employerName()));

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

    /**
     * Normalizes title+employer (lowercased, punctuation stripped, whitespace collapsed)
     * so the same real vacancy posted separately per city — different hh_id each time —
     * can still be recognized as "the same posting" for scrape/AI-verdict reuse. City
     * names deliberately aren't stripped out here: they rarely appear in the title itself,
     * and this is a best-effort MVP heuristic, not a guaranteed match.
     */
    static String computeDedupKey(String title, String employerName) {
        String t = normalizeForDedup(title);
        String e = normalizeForDedup(employerName);
        if (t.isEmpty()) return "";
        return t + "|" + e;
    }

    private static String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.toLowerCase()
            .replaceAll("[^a-zа-яё0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
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

        int saved = 0;
        for (Vacancy v : filtered) {
            if (vacancyRepo.existsByHhIdPersonSearch(v.getHhId(), job.personName, job.searchName)) continue;
            v.setPerson(job.personName);
            v.setSearchName(job.searchName);
            v.setUserId(job.isGlobal ? null : job.userId);
            v.setSearchId(job.searchId);
            v.setRemote(job.isRemote());
            v.setSourceQuery(job.searchName);
            v.setScrapeStatus("pending");
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

    // ScraperClient.scrape() tags connectivity/transport failures (sidecar down, refused,
    // timed out, unparseable response) with this prefix, distinct from per-vacancy content
    // failures like "not_found"/"no_job_posting_data" where the sidecar is clearly up and
    // working, just this one URL is bad. Only the former means retrying more URLs in this
    // same run is pointless — the whole sidecar is unreachable, not just this vacancy.
    private static final String SCRAPER_CLIENT_ERROR_PREFIX = "client_error";
    private static final int MAX_CONSECUTIVE_SCRAPE_FAILURES = 3;

    /**
     * Scrape full content for rows still pending (or previously failed) for this job.
     * Reuses already-scraped content for the same hh_id if a different (person,
     * search) already fetched it, instead of hitting the scraper sidecar again.
     *
     * Bails out early after several consecutive connectivity failures instead of
     * grinding through the rest of the batch — each scrape can block for up to the
     * configured HTTP read timeout, so a genuinely down/hung sidecar could otherwise
     * stall this step for maxPerRun × timeout (worst case, well over an hour).
     * Unscraped rows are simply left 'pending' and picked up on the next run.
     */
    private int scrapePending(SearchJob job) {
        int count = 0;
        int consecutiveFailures = 0;
        List<Vacancy> pending = vacancyRepo.findScrapePending(job.personName, job.searchName, runtimeConfig.getMaxPerRun());
        for (Vacancy v : pending) {
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
            } else {
                v.setScrapeStatus("not_found".equals(r.reason()) ? "not_found" : "failed");
                log.warn("Скрейпинг {} ({} · {}) не удался: {}", v.getHhId(), job.personName, job.searchName, r.reason());
                if (r.reason() != null && r.reason().startsWith(SCRAPER_CLIENT_ERROR_PREFIX)) {
                    consecutiveFailures++;
                } else {
                    consecutiveFailures = 0;
                }
            }
            vacancyRepo.updateScraped(v);
            count++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_SCRAPE_FAILURES) {
                log.warn("Скрейпинг ({} · {}) остановлен после {} подряд ошибок соединения — сайдкар недоступен, оставшиеся {} вакансий останутся в очереди",
                    job.personName, job.searchName, consecutiveFailures, pending.size() - count);
                break;
            }
        }
        return count;
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
        return stripped
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
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

            totalAnalyzed += analyzeBatchWithDedup(batch, job);
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
    private int analyzeBatchWithDedup(List<Vacancy> batch, SearchJob job) {
        String criteriaHash = aiAnalyzer.computeCriteriaHash(job);
        // Every vacancy in this batch gets the same hash (it's a property of the job, not
        // the vacancy) — one batched UPDATE instead of one round-trip per vacancy.
        vacancyRepo.updateCriteriaHashBatch(batch.stream().map(Vacancy::getId).toList(), criteriaHash);

        List<Vacancy> needsAi = new ArrayList<>();
        int deduped = 0;

        for (Vacancy v : batch) {
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
        if (deduped > 0) {
            log.info("AI-дедуп ({} · {}): {} вакансий переиспользовано без вызова AI", job.personName, job.searchName, deduped);
            metrics.recordVacanciesDeduped(deduped);
        }

        int aiAnalyzed = 0;
        if (!needsAi.isEmpty()) {
            for (var r : aiAnalyzer.analyzeBatch(needsAi, job)) {
                vacancyRepo.updateAiResult(r.hhId(), job.personName, job.searchName, r.score(), r.verdict(), r.reason());
                aiAnalyzed++;
            }
            metrics.recordVacanciesAnalyzed(aiAnalyzed);
        }
        return deduped + aiAnalyzed;
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
        String salary = formatSalary(v);
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

    private String formatSalary(Vacancy v) {
        boolean hasFrom = v.getSalaryFrom() != null && v.getSalaryFrom() > 0;
        boolean hasTo = v.getSalaryTo() != null && v.getSalaryTo() > 0;
        if (!hasFrom && !hasTo) return "з/п не указана";
        StringBuilder sb = new StringBuilder();
        if (hasFrom) sb.append("от ").append(v.getSalaryFrom());
        if (hasTo) sb.append(" до ").append(v.getSalaryTo());
        if (v.getCurrency() != null) sb.append(" ").append(v.getCurrency());
        return sb.toString();
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
