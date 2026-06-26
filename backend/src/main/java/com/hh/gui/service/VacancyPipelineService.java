package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.ai.VacancyAiAnalyzer.SearchProfile;
import com.hh.gui.client.HhApiClient;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main pipeline: collect → AI analyze → notify.
 * Replaces both Python collector and Hermes cron AI analysis.
 */
@Service
public class VacancyPipelineService {

    private static final Logger log = LoggerFactory.getLogger(VacancyPipelineService.class);

    private final HhApiClient hhApiClient;
    private final VacancyAiAnalyzer aiAnalyzer;
    private final VacancyRepository vacancyRepo;
    private final TelegramNotifier telegramNotifier;

    @Value("${app.pipeline.batch-size:10}")
    private int batchSize;

    @Value("${app.notifications.enabled:false}")
    private boolean notificationsEnabled;

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
    }

    public VacancyPipelineService(HhApiClient hhApiClient, VacancyAiAnalyzer aiAnalyzer,
                                   VacancyRepository vacancyRepo, TelegramNotifier telegramNotifier) {
        this.hhApiClient = hhApiClient;
        this.aiAnalyzer = aiAnalyzer;
        this.vacancyRepo = vacancyRepo;
        this.telegramNotifier = telegramNotifier;
    }

    /**
     * Scheduled pipeline: runs every 10 minutes.
     * Analyzes up to 30 pending vacancies per invocation to avoid SQLite locks.
     */
    @Scheduled(fixedDelay = 600000) // 10 minutes
    public void scheduledPipeline() {
        try {
            com.hh.gui.ai.VacancyAiAnalyzer.SearchProfile aiProfile = com.hh.gui.ai.VacancyAiAnalyzer.SearchProfile.defaultProfile();
            SearchProfile profile = new SearchProfile();
            profile.city = aiProfile.city;
            profile.priorityDistricts = aiProfile.priorityDistricts;
            profile.skills = aiProfile.skills;
            profile.notSuitable = aiProfile.notSuitable;
            profile.salaryMin = aiProfile.salaryMin;
            profile.queries = SearchQuery.defaultQueries();
            log.info("=== Scheduled pipeline start ===");
            runFullPipeline(profile);
            log.info("=== Scheduled pipeline end ===");
        } catch (Exception e) {
            log.error("Scheduled pipeline failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Full pipeline: collect all vacancies → AI analyze → send report.
     * Each step runs in its own short transaction to avoid long-held locks.
     */
    public PipelineResult runFullPipeline(SearchProfile profile) {
        // Convert AI profile to local profile with default queries if needed
        if (profile.queries == null || profile.queries.isEmpty()) {
            profile.queries = SearchQuery.defaultQueries();
        }
        log.info("=== Pipeline start ===");

        // Step 1: Collect all vacancies via HH API
        List<Vacancy> allCollected = collectAll(profile);
        log.info("Step 1: Collected {} vacancies total", allCollected.size());

        // Step 2: Save new ones to DB (short transaction)
        int newCount = saveNewVacancies(allCollected);
        log.info("Step 2: {} new vacancies saved", newCount);

        // Step 3: AI analyze pending (runs without holding a long transaction)
        int analyzed = analyzePending(profile);
        log.info("Step 3: {} vacancies AI-analyzed", analyzed);

        // Step 4: Get approved for notification (min AI score = 50, max 10 per batch)
        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(50, 10);
        log.info("Step 4: {} approved unnotified vacancies", approved.size());

        // Step 5: Send Telegram report
        if (!approved.isEmpty()) {
            sendReport(approved, profile);
        }

        PipelineResult result = new PipelineResult();
        result.collected = allCollected.size();
        result.newVacancies = newCount;
        result.analyzed = analyzed;
        result.approved = approved.size();
        return result;
    }

    /**
     * Collect vacancies for all search queries (flat list, no local/remote split).
     */
    private List<Vacancy> collectAll(SearchProfile profile) {
        List<Vacancy> all = new ArrayList<>();

        if (profile.queries == null || profile.queries.isEmpty()) {
            log.warn("No search queries configured");
            return all;
        }

        for (SearchQuery sq : profile.queries) {
            log.debug("Collecting: query='{}' area={} schedule={} remote={}",
                    sq.query, sq.area, sq.schedule, sq.isRemote);
            List<Vacancy> batch = hhApiClient.fetchRss(sq.query, sq.area, sq.schedule, sq.salaryMin);
            for (Vacancy v : batch) {
                v.setSourceQuery(sq.query);
                v.setRemote(sq.isRemote);
            }
            all.addAll(batch);
        }

        // Deduplicate by HH ID
        Map<String, Vacancy> seen = new LinkedHashMap<>();
        for (Vacancy v : all) {
            seen.putIfAbsent(v.getHhId(), v);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Save only new vacancies (not in DB yet).
     */
    @Transactional
    private int saveNewVacancies(List<Vacancy> vacancies) {
        int saved = 0;
        for (Vacancy v : vacancies) {
            if (!vacancyRepo.existsByHhId(v.getHhId())) {
                try {
                    vacancyRepo.save(v);
                    saved++;
                } catch (Exception e) {
                    // Duplicate or error — skip
                }
            }
        }
        return saved;
    }

    /**
     * AI-analyze pending vacancies (limited to maxPerRun per invocation).
     */
    private int analyzePending(SearchProfile profile) {
        int totalAnalyzed = 0;
        int maxPerRun = 30; // limit per pipeline run to avoid burning all quota
        int processed = 0;

        while (processed < maxPerRun) {
            int remaining = maxPerRun - processed;
            int currentBatchSize = Math.min(batchSize, remaining);
            List<Vacancy> batch = vacancyRepo.findPending(currentBatchSize);
            if (batch.isEmpty()) break;

            VacancyAiAnalyzer.SearchProfile aiProfile = new VacancyAiAnalyzer.SearchProfile();
            aiProfile.city = profile.city;
            aiProfile.priorityDistricts = profile.priorityDistricts;
            aiProfile.skills = profile.skills;
            aiProfile.notSuitable = profile.notSuitable;
            aiProfile.salaryMin = profile.salaryMin;
            aiProfile.schedule = profile.schedule;

            var results = aiAnalyzer.analyzeBatch(batch, aiProfile);
            for (var r : results) {
                vacancyRepo.updateAiResult(r.hhId(), r.score(), r.verdict(), r.reason());
                totalAnalyzed++;
            }
            processed += batch.size();
            log.debug("AI progress: {}/{} this run", processed, maxPerRun);
        }
        return totalAnalyzed;
    }

    /**
     * Send Telegram report and mark as notified.
     * Skipped if notifications are disabled (app.notifications.enabled=false).
     */
    private void sendReport(List<Vacancy> approved, SearchProfile profile) {
        if (!notificationsEnabled) {
            log.info("Step 5: Notifications disabled — skipping Telegram report ({} approved)", approved.size());
            return;
        }
        String report = formatReport(approved, profile);
        boolean sent = telegramNotifier.send(report);
        if (sent) {
            List<String> ids = approved.stream().map(Vacancy::getHhId).collect(Collectors.toList());
            vacancyRepo.markNotified(ids);
            log.info("Step 5: Report sent ({} vacancies)", approved.size());
        }
    }

    private String formatReport(List<Vacancy> vacancies, SearchProfile profile) {
        List<Vacancy> local = vacancies.stream().filter(v -> !v.isRemote()).collect(Collectors.toList());
        List<Vacancy> remote = vacancies.stream().filter(Vacancy::isRemote).collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>Новые вакансии для ").append(escapeHtml(profile.city)).append("</b>\n\n");

        if (!local.isEmpty()) {
            sb.append("🏢 Найдено ").append(local.size()).append(" вакансий в ").append(escapeHtml(profile.city)).append(":\n\n");
            for (int i = 0; i < local.size(); i++) {
                appendVacancy(sb, i + 1, local.get(i));
            }
        }

        if (!remote.isEmpty()) {
            sb.append("\n🏠 Найдено ").append(remote.size()).append(" удалённых вакансий:\n\n");
            for (int i = 0; i < remote.size(); i++) {
                appendVacancy(sb, i + 1, remote.get(i));
            }
        }

        return sb.toString();
    }

    private void appendVacancy(StringBuilder sb, int num, Vacancy v) {
        int score = v.getAiScore() != null ? v.getAiScore() : 0;
        String emoji = score >= 80 ? "🟢" : score >= 60 ? "🟡" : "🟠";
        String salary = formatSalary(v);
        String company = v.getCompany() != null ? escapeHtml(v.getCompany()) : "компания не указана";
        String reason = v.getAiReason() != null ? escapeHtml(v.getAiReason()) : "";

        sb.append(String.format("%d. %s <b>[%d%%]</b> %s\n", num, emoji, score, escapeHtml(v.getTitle())));
        sb.append(String.format("   🏢 %s | 💰 %s\n", company, salary));
        sb.append(String.format("   💡 %s\n", reason));
        sb.append(String.format("   🔗 %s\n\n", v.getUrl()));
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String formatSalary(Vacancy v) {
        if (v.getSalaryFrom() == null && v.getSalaryTo() == null) return "з/п не указана";
        StringBuilder sb = new StringBuilder();
        if (v.getSalaryFrom() != null && v.getSalaryFrom() > 0) sb.append("от ").append(v.getSalaryFrom());
        if (v.getSalaryTo() != null && v.getSalaryTo() > 0) sb.append(" до ").append(v.getSalaryTo());
        if (v.getCurrency() != null) sb.append(" ").append(v.getCurrency());
        return sb.length() > 0 ? sb.toString() : "з/п не указана";
    }

    /**
     * Re-analyze all eligible vacancies (ai_verdict != 'no' AND status != 'rejected').
     * Resets AI results to pending, then runs AI analysis in batches.
     * Sends Telegram report when done.
     *
     * @return ReanalyzeResult with counts
     */
    @Transactional
    public ReanalyzeResult reanalyzeAll(SearchProfile profile) {
        log.info("=== Re-analyze start ===");

        // Step 1: Reset AI results for eligible vacancies
        int resetCount = vacancyRepo.resetAiForRescan();
        log.info("Step 1: Reset {} vacancies for re-analysis", resetCount);

        if (resetCount == 0) {
            log.info("No vacancies to re-analyze");
            ReanalyzeResult empty = new ReanalyzeResult();
            empty.reset = 0;
            empty.analyzed = 0;
            empty.approved = 0;
            return empty;
        }

        // Step 2: AI analyze all pending (which are now the reset ones)
        int totalAnalyzed = 0;
        int maxPerRun = resetCount; // analyze all reset vacancies
        int processed = 0;

        while (processed < maxPerRun) {
            int remaining = maxPerRun - processed;
            int currentBatchSize = Math.min(batchSize, remaining);
            List<Vacancy> batch = vacancyRepo.findPending(currentBatchSize);
            if (batch.isEmpty()) break;

            VacancyAiAnalyzer.SearchProfile aiProfile = new VacancyAiAnalyzer.SearchProfile();
            aiProfile.city = profile.city;
            aiProfile.priorityDistricts = profile.priorityDistricts;
            aiProfile.skills = profile.skills;
            aiProfile.notSuitable = profile.notSuitable;
            aiProfile.salaryMin = profile.salaryMin;
            aiProfile.schedule = profile.schedule;

            var results = aiAnalyzer.analyzeBatch(batch, aiProfile);
            for (var r : results) {
                vacancyRepo.updateAiResult(r.hhId(), r.score(), r.verdict(), r.reason());
                totalAnalyzed++;
            }
            processed += batch.size();
            log.info("Re-analyze progress: {}/{}", processed, maxPerRun);
        }
        log.info("Step 2: {} vacancies AI-analyzed", totalAnalyzed);

        // Step 3: Get approved for notification
        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(50, 10);
        log.info("Step 3: {} approved unnotified vacancies", approved.size());

        // Step 4: Send Telegram report
        if (!approved.isEmpty()) {
            sendReport(approved, profile);
        }

        ReanalyzeResult result = new ReanalyzeResult();
        result.reset = resetCount;
        result.analyzed = totalAnalyzed;
        result.approved = approved.size();
        return result;
    }

    /**
     * Single search query entry.
     */
    public static class SearchQuery {
        public String query;
        public int area;
        public String schedule;
        public int salaryMin;
        public boolean isRemote;
        public List<String> excludeWords;

        public static List<SearchQuery> defaultQueries() {
            List<SearchQuery> queries = new ArrayList<>();
            // Offline Ufa searches
            for (String q : List.of("продавец", "консультант", "оператор", "администратор", "менеджер")) {
                SearchQuery sq = new SearchQuery();
                sq.query = q;
                sq.area = 99; // Ufa
                sq.schedule = "fullTime";
                sq.salaryMin = 0;
                sq.isRemote = false;
                sq.excludeWords = List.of("водитель", "грузчик", "разнорабочий", "курьер", "вахта",
                        "кол-центр", "call-центр", "call center", "телефон", "холодн",
                        "супермаркет", "гипермаркет", "пятёрочка", "магнит", "лента", "дикси",
                        "перекрёсток", "склад", "комплектовщик", "сборщик", "фасовщик", "упаковщик",
                        "физическ", "ручной труд", "производство", "завод", "цех");
                queries.add(sq);
            }
            // Remote Russia searches
            for (String q : List.of("оператор ПК", "модератор", "администратор интернет-магазин",
                    "специалист поддержки", "диспетчер", "помощник руководителя", "секретарь",
                    "менеджер маркетплейс", "контент-менеджер", "специалист чат")) {
                SearchQuery sq = new SearchQuery();
                sq.query = q;
                sq.area = 113; // Russia
                sq.schedule = "remote";
                sq.salaryMin = 40000;
                sq.isRemote = true;
                sq.excludeWords = List.of("кол-центр", "call-центр", "call center", "телефон", "холодн",
                        "продаж", "водитель", "курьер", "грузчик", "разнорабочий", "вахта",
                        "склад", "производство", "завод", "цех", "фриланс",
                        "1С программист", "программист", "разработчик", "тестировщик",
                        "системный администратор", "DevOps", "аналитик данных", "Data Scientist");
                queries.add(sq);
            }
            return queries;
        }
    }

    /**
     * Local search profile config (extends AI SearchProfile with queries).
     * Replaces localQueries/remoteQueries with flat queries list.
     */
    public static class SearchProfile extends VacancyAiAnalyzer.SearchProfile {
        public List<SearchQuery> queries;
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
