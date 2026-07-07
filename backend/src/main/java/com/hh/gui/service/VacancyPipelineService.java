package com.hh.gui.service;

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

    @Value("${app.pipeline.batch-size:10}")
    private int batchSizeDefault;

    @Value("${app.notifications.enabled:false}")
    private boolean notificationsEnabledDefault;

    public VacancyPipelineService(HhApiClient hhApiClient, ScraperClient scraperClient, VacancyAiAnalyzer aiAnalyzer,
                                   VacancyRepository vacancyRepo, TelegramNotifier telegramNotifier,
                                   RuntimeConfig runtimeConfig) {
        this.hhApiClient = hhApiClient;
        this.scraperClient = scraperClient;
        this.aiAnalyzer = aiAnalyzer;
        this.vacancyRepo = vacancyRepo;
        this.telegramNotifier = telegramNotifier;
        this.runtimeConfig = runtimeConfig;
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

    /**
     * Scrape full content for rows still pending (or previously failed) for this job.
     * Reuses already-scraped content for the same hh_id if a different (person,
     * search) already fetched it, instead of hitting the scraper sidecar again.
     */
    private int scrapePending(SearchJob job) {
        int count = 0;
        List<Vacancy> pending = vacancyRepo.findScrapePending(job.personName, job.searchName, runtimeConfig.getMaxPerRun());
        for (Vacancy v : pending) {
            Optional<Vacancy> existing = vacancyRepo.findFirstScrapedByHhId(v.getHhId());
            if (existing.isPresent() && !existing.get().getId().equals(v.getId())) {
                copyScraped(existing.get(), v);
            } else {
                ScrapeResult r = scraperClient.scrape(v.getHhId());
                if (r.ok()) {
                    applyScrapeResult(v, r);
                    v.setScrapeStatus("ok");
                } else {
                    v.setScrapeStatus("not_found".equals(r.reason()) ? "not_found" : "failed");
                    log.warn("Скрейпинг {} ({} · {}) не удался: {}", v.getHhId(), job.personName, job.searchName, r.reason());
                }
            }
            vacancyRepo.updateScraped(v);
            count++;
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
        if (r.title() != null && !r.title().isBlank()) v.setTitle(r.title());
        v.setCompany(r.employerName());
        v.setEmployerName(r.employerName());
        v.setDescription(htmlToText(r.descriptionHtml()));
        if (r.salaryFrom() != null) v.setSalaryFrom(r.salaryFrom());
        if (r.salaryTo() != null) v.setSalaryTo(r.salaryTo());
        if (r.currency() != null) v.setCurrency(r.currency());
        v.setSalaryGross(Boolean.TRUE.equals(r.salaryGross()));
        v.setAddress(String.join(", ", nonBlank(r.city(), r.street())));
        v.setDistrict(extractDistrict(String.join(" ", nonBlank(r.city(), r.street(), htmlToText(r.descriptionHtml())))));
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

            for (var r : aiAnalyzer.analyzeBatch(batch, job)) {
                vacancyRepo.updateAiResult(r.hhId(), job.personName, job.searchName, r.score(), r.verdict(), r.reason());
                totalAnalyzed++;
            }
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

            for (var r : aiAnalyzer.analyzeBatch(batch, job)) {
                vacancyRepo.updateAiResult(r.hhId(), job.personName, job.searchName, r.score(), r.verdict(), r.reason());
                totalAnalyzed++;
            }
            batchNum++;
        }
        return totalAnalyzed;
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

    private void sendReport(List<Vacancy> approved, SearchJob job) {
        if (!runtimeConfig.isNotificationsEnabled()) {
            log.info("Уведомления отключены — отчёт ({} · {}, {} одобренных) не отправлен",
                job.personName, job.searchName, approved.size());
            return;
        }
        String report = formatReport(approved, job);
        boolean sent = telegramNotifier.send(report);
        if (sent) {
            vacancyRepo.markNotified(approved.stream().map(Vacancy::getId).collect(Collectors.toList()));
            log.info("Отчёт отправлен ({} · {}, {} вакансий)", job.personName, job.searchName, approved.size());
        }
    }

    private String formatReport(List<Vacancy> vacancies, SearchJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>").append(escapeHtml(job.personName)).append(" · ")
          .append(escapeHtml(job.searchName)).append("</b>\n\n");
        for (int i = 0; i < vacancies.size(); i++) {
            appendVacancy(sb, i + 1, vacancies.get(i));
        }
        return sb.toString();
    }

    private void appendVacancy(StringBuilder sb, int num, Vacancy v) {
        int score = v.getAiScore() != null ? v.getAiScore() : 0;
        String emoji = score >= 80 ? "🟢" : score >= 60 ? "🟡" : "🟠";
        String salary = formatSalary(v);
        String company = v.getCompany() != null && !v.getCompany().isEmpty() ? escapeHtml(v.getCompany()) : "компания не указана";
        String reason = v.getAiReason() != null ? escapeHtml(v.getAiReason()) : "";

        sb.append(String.format("%d. %s <b>[%d%%]</b> %s\n", num, emoji, score, escapeHtml(v.getTitle())));
        sb.append(String.format("   🏢 %s | 💰 %s\n", company, salary));
        sb.append(String.format("   💡 %s\n", reason));
        sb.append(String.format("   🔗 %s\n\n", v.getUrl()));
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
