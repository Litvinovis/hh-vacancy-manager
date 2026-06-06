package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.ai.VacancyAiAnalyzer.SearchProfile;
import com.hh.gui.client.HhApiClient;
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

    @Value("${app.pipeline.batch-size:20}")
    private int batchSize;

    public VacancyPipelineService(HhApiClient hhApiClient, VacancyAiAnalyzer aiAnalyzer,
                                   VacancyRepository vacancyRepo, TelegramNotifier telegramNotifier) {
        this.hhApiClient = hhApiClient;
        this.aiAnalyzer = aiAnalyzer;
        this.vacancyRepo = vacancyRepo;
        this.telegramNotifier = telegramNotifier;
    }

    /**
     * Full pipeline: collect all vacancies → AI analyze → send report.
     */
    @Transactional
    public PipelineResult runFullPipeline(SearchProfile profile) {
        log.info("=== Pipeline start ===");

        // Step 1: Collect all vacancies via HH API
        List<Vacancy> allCollected = collectAll(profile);
        log.info("Step 1: Collected {} vacancies total", allCollected.size());

        // Step 2: Save new ones to DB
        int newCount = saveNewVacancies(allCollected);
        log.info("Step 2: {} new vacancies saved", newCount);

        // Step 3: AI analyze pending
        int analyzed = analyzePending(profile);
        log.info("Step 3: {} vacancies AI-analyzed", analyzed);

        // Step 4: Get approved for notification
        List<Vacancy> approved = vacancyRepo.findUnnotifiedApproved(profile.salaryMin, 20);
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
     * Collect vacancies for all queries (local + remote).
     */
    private List<Vacancy> collectAll(SearchProfile profile) {
        List<Vacancy> all = new ArrayList<>();

        // Local search (Ufa)
        if (profile.localQueries != null && !profile.localQueries.isEmpty()) {
            for (String query : profile.localQueries) {
                List<Vacancy> batch = hhApiClient.fetchAll(query, profile.area, profile.schedule, profile.salaryMin);
                for (Vacancy v : batch) {
                    v.setSourceQuery(query);
                    v.setRemote(false);
                }
                all.addAll(batch);
            }
        }

        // Remote search (Russia)
        if (profile.remoteQueries != null && !profile.remoteQueries.isEmpty()) {
            for (String query : profile.remoteQueries) {
                List<Vacancy> batch = hhApiClient.fetchAll(query, profile.remoteArea, "remote", profile.salaryMin);
                for (Vacancy v : batch) {
                    v.setSourceQuery(query);
                    v.setRemote(true);
                }
                all.addAll(batch);
            }
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
     * AI-analyze pending vacancies.
     */
    private int analyzePending(SearchProfile profile) {
        int totalAnalyzed = 0;
        int pending;
        do {
            pending = vacancyRepo.countPending();
            if (pending == 0) break;

            List<Vacancy> batch = vacancyRepo.findPending(batchSize);
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
        } while (pending > 0);
        return totalAnalyzed;
    }

    /**
     * Send Telegram report and mark as notified.
     */
    private void sendReport(List<Vacancy> approved, SearchProfile profile) {
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
        sb.append("🔍 *Новые вакансии для ").append(profile.city).append("*\n\n");

        if (!local.isEmpty()) {
            sb.append("🏢 Найдено ").append(local.size()).append(" вакансий в ").append(profile.city).append(":\n\n");
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
        String company = v.getCompany() != null ? v.getCompany() : "компания не указана";
        String reason = v.getAiReason() != null ? v.getAiReason() : "";

        sb.append(String.format("%d. %s *[%d%%]* %s\n", num, emoji, score, v.getTitle()));
        sb.append(String.format("   🏢 %s | 💰 %s\n", company, salary));
        sb.append(String.format("   💡 %s\n", reason));
        sb.append(String.format("   🔗 %s\n\n", v.getUrl()));
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
     * Search profile with all needed fields.
     */
    public static class SearchProfile {
        public String city;
        public List<String> priorityDistricts;
        public List<String> skills;
        public List<String> notSuitable;
        public int salaryMin;
        public String schedule;
        public List<String> localQueries;
        public List<String> remoteQueries;
        public int area;
        public int remoteArea;
    }

    public static class PipelineResult {
        public int collected;
        public int newVacancies;
        public int analyzed;
        public int approved;
    }
}
