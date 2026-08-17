package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.HhApiClient;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.client.TelegramClient;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.util.DedupKeys;
import com.hh.gui.util.TelegramPostParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds vacancy candidates and saves them as unprocessed rows. Three sources, one
 * job each: hh.ru RSS queries, a saved hh.ru search URL, and public Telegram
 * channels. What happens to a candidate afterwards — scraping, AI analysis,
 * deduplication, publishing — is nobody's business here.
 *
 * Split out of VacancyPipelineService as the third of the audit's three seams, and
 * the largest: three sources' worth of collection logic, previously interleaved with
 * the pipeline orchestration that calls it.
 *
 * The seven collaborators are inherent rather than accidental — discovery is the one
 * part of the system that genuinely talks to every external source: two HTTP clients,
 * a browser sidecar, the LLM prescreen that decides which listings are worth scraping
 * at all, plus persistence and metrics.
 */
@Service
public class VacancyDiscovery {

    private static final Logger log = LoggerFactory.getLogger(VacancyDiscovery.class);

    /** Hard ceiling on pages walked per URL search — see fromUrl for why every page is
     *  visited rather than stopping early on already-seen results. */
    public static final int MAX_URL_SEARCH_PAGES = 10;

    private final HhApiClient hhApiClient;
    private final ScraperClient scraperClient;
    private final TelegramClient telegramClient;
    private final VacancyAiAnalyzer aiAnalyzer;
    private final VacancyRepository vacancyRepo;
    private final TelegramMetrics telegramMetrics;
    private final ChannelEngagementTracker engagementTracker;
    private final ScrapeCooldown scrapeCooldown;
    private final com.hh.gui.ai.AiMetrics metrics;

    public VacancyDiscovery(HhApiClient hhApiClient, ScraperClient scraperClient, TelegramClient telegramClient,
                            VacancyAiAnalyzer aiAnalyzer, VacancyRepository vacancyRepo,
                            TelegramMetrics telegramMetrics, ChannelEngagementTracker engagementTracker,
                            ScrapeCooldown scrapeCooldown, com.hh.gui.ai.AiMetrics metrics) {
        this.hhApiClient = hhApiClient;
        this.scraperClient = scraperClient;
        this.telegramClient = telegramClient;
        this.aiAnalyzer = aiAnalyzer;
        this.vacancyRepo = vacancyRepo;
        this.telegramMetrics = telegramMetrics;
        this.engagementTracker = engagementTracker;
        this.scrapeCooldown = scrapeCooldown;
        this.metrics = metrics;
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
    // No @Transactional — see discoverFromUrl above for why it was a no-op here.
    public int fromRss(SearchJob job) {
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
        metrics.recordVacanciesCollected("hh.ru", saved);
        return saved;
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
    // No @Transactional: it used to be here and did nothing. Spring's proxy-based
    // transaction advice only applies to public methods, and both callers of this one
    // (runFullPipeline / runFullPipelineFromUrl) are in this same class, so the call
    // never goes through the proxy anyway. Each vacancyRepo.save below therefore
    // auto-commits on its own — which is also what the per-save try/catch already
    // assumes, since it deliberately keeps going after one bad row.
    public int fromUrl(SearchJob job, String url, int maxPages) {
        if (scrapeCooldown.isCoolingDown()) {
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
        metrics.recordVacanciesCollected("hh.ru", saved);
        return saved;
    }

    // No @Transactional — see discoverFromUrl above for why it was a no-op here.
    public int fromTelegram(SearchJob job, List<String> channels) {
        if (channels == null || channels.isEmpty()) return 0;

        List<Vacancy> candidates = new ArrayList<>();
        for (String channel : channels) {
            TelegramClient.ChannelResult result = telegramClient.fetchChannel(channel, 100);
            if (!result.ok()) {
                log.warn("Telegram-канал @{} ({} · {}) недоступен: {}", channel, job.personName, job.searchName, result.reason());
                continue;
            }
            // SMM engagement snapshot for this SOURCE channel (where vacancies are found,
            // not the app's own output channel — see ChannelEngagementTracker.checkOwnChannels for that) —
            // summed across whatever's currently visible in the scrape, refreshed on every
            // scheduled re-visit (not just at discovery), so already-known posts' growing
            // view/reaction counts still show up in Grafana even though they're skipped
            // below for candidate insertion.
            engagementTracker.recordFrom(channel, result.items());

            for (TelegramClient.TelegramMessage msg : result.items()) {
                if (msg.text() == null || msg.text().isBlank()) continue;

                TelegramPostParser.HhLink hhLink = TelegramPostParser.hhLink(msg.text());
                Vacancy v = new Vacancy();
                v.setCreatedAt(Instant.now().toString());
                v.setPerson(job.personName);
                v.setSearchName(job.searchName);
                v.setUserId(job.isGlobal ? null : job.userId);
                v.setSearchId(job.searchId);
                v.setRemote(job.isRemote());
                v.setSourceQuery(job.searchName);
                v.setUrl(msg.link());

                if (hhLink != null) {
                    // Path A: reuse the normal hh.ru pipeline — this Telegram post was
                    // just how the hh_id was discovered. The link readers get is the real
                    // hh.ru vacancy URL (matches how RSS/URL discovery set it), not the
                    // Telegram post it happened to be found in.
                    v.setUrl(hhLink.url());
                    v.setHhId(hhLink.hhId());
                    v.setTitle(TelegramPostParser.title(msg.text()));
                    v.setSource("hh");
                    v.setStatus("new");
                    v.setScrapeStatus("pending");
                    v.setDedupKey(DedupKeys.compute(v.getTitle(), null));
                } else {
                    // Path B: the post itself is the only source — never write its own
                    // wording anywhere downstream except AI analysis input; the public
                    // post text this pipeline eventually sends out is AI-generated from
                    // extracted facts, not a copy (see VacancyAiAnalyzer/ChannelPublisher.send).
                    // Some channels (e.g. kadrout) are themselves aggregators posting only a
                    // teaser with a link to the full listing on their own site — verified
                    // live, that link (not a job board we have a scraper for, so still Path
                    // B) is a far more useful "read more" destination for readers than the
                    // truncated Telegram post itself, so prefer it as the URL when present.
                    String externalUrl = TelegramPostParser.externalUrl(msg.text());
                    if (externalUrl != null) v.setUrl(externalUrl);
                    String title = TelegramPostParser.title(msg.text());
                    String employer = TelegramPostParser.employer(msg.text(), title, channel);
                    v.setHhId(msg.id());
                    v.setTitle(title);
                    v.setCompany(employer);
                    v.setDescription(msg.text());
                    TelegramPostParser.Salary salary = TelegramPostParser.salary(msg.text());
                    if (salary != null) {
                        v.setSalaryFrom(salary.from());
                        v.setSalaryTo(salary.to());
                        v.setCurrency(salary.currency());
                    }
                    v.setSource("telegram");
                    v.setStatus("new");
                    v.setScrapeStatus("ok");
                    v.setAiVerdict("pending");
                    v.setAiScore(0);
                    // Verified live: description-hash dedup missed repeat postings of the
                    // SAME job on the SAME channel — a vacancy-bot channel re-generates its
                    // wording (different intro line, different markdown structure) each time
                    // it re-posts, so real line-similarity between copies measured ~0.53, well
                    // under dedupeBySimilarity's 0.85 threshold; 12 copies of one "Асессор"
                    // posting all got approved separately. Without a real extracted employer,
                    // the description hash was the only differentiator — falling back to the
                    // title+employer key here (same two-arg form RSS discovery already uses
                    // pre-scrape) makes repeats on the same channel share one key instead.
                    // Only applied when employer IS the channel fallback ("@channel"): once a
                    // real employer is extracted, different postings from it deserve their own
                    // description-hash keys as normal.
                    v.setDedupKey(employer.startsWith("@")
                        ? DedupKeys.compute(v.getTitle(), employer)
                        : DedupKeys.compute(v.getTitle(), employer, msg.text()));
                }
                candidates.add(v);
            }
        }
        if (candidates.isEmpty()) return 0;

        List<Vacancy> filtered = filterExcluded(candidates, job.excludeWords);
        Set<String> knownHhIds = vacancyRepo.findExistingHhIds(
            filtered.stream().map(Vacancy::getHhId).toList(), job.personName, job.searchName);
        List<Vacancy> fresh = filtered.stream().filter(v -> !knownHhIds.contains(v.getHhId())).toList();

        int saved = 0;
        for (Vacancy v : fresh) {
            try {
                vacancyRepo.save(v);
                saved++;
                // Path A (source="hh") has a real hh.ru numeric hh_id, which
                // channelFromHhId never matches (it only recognizes the Path B
                // "tg_<channel>_<id>" format) — recordCollected no-ops on the null it
                // returns, so without this branch Path A candidates found via Telegram
                // were invisible to every collection metric: not counted here (wrong
                // format) AND not counted by fromRss/fromUrl's recordVacanciesCollected
                // either (this save happens here, not there).
                if ("hh".equals(v.getSource())) {
                    metrics.recordVacanciesCollected("hh.ru", 1);
                } else {
                    telegramMetrics.recordCollected(TelegramPostParser.channelFromHhId(v.getHhId()));
                }
            } catch (Exception e) {
                log.warn("Не удалось сохранить Telegram-кандидата {} ({} · {}): {}",
                    v.getHhId(), job.personName, job.searchName, e.getMessage());
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
            // Some listings hide the excluded trade behind a generic title ("Менеджер /
            // Помощник руководителя") and only give it away in the employer name (e.g.
            // "Агентство Недвижимости ..." recruiting under a vague title) — title alone
            // missed those.
            String employer = h.employerName() != null ? h.employerName().toLowerCase() : "";
            if (lower.stream().noneMatch(w -> title.contains(w) || employer.contains(w))) result.add(h);
        }
        return result;
    }

    /**
     * Drop candidates whose title or employer name contains an excluded word — before
     * scraping, not just before AI. Checks the employer too (see filterExcludedHits):
     * some listings hide the excluded trade behind a generic title and only give it
     * away in who's hiring.
     */
    private List<Vacancy> filterExcluded(List<Vacancy> vacancies, List<String> excludeWords) {
        if (excludeWords == null || excludeWords.isEmpty()) return vacancies;
        List<String> lower = excludeWords.stream().map(String::toLowerCase).toList();
        List<Vacancy> result = new ArrayList<>();
        for (Vacancy v : vacancies) {
            String title = v.getTitle() != null ? v.getTitle().toLowerCase() : "";
            String employer = v.employerOrCompany().toLowerCase();
            if (lower.stream().noneMatch(w -> title.contains(w) || employer.contains(w))) result.add(v);
        }
        return result;
    }
}
