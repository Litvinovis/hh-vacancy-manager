package com.hh.gui.controller;

import com.hh.gui.ai.AiProviderManager;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.User;
import com.hh.gui.service.SearchProfileFactory;
import com.hh.gui.service.VacancyPipelineService;
import com.hh.gui.service.VacancyPipelineService.PipelineResult;
import com.hh.gui.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for pipeline control and status.
 * Scheduled execution lives in PipelineScheduler.
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final VacancyPipelineService pipelineService;
    private final VacancyRepository vacancyRepo;
    private final SearchProfileFactory profileFactory;
    private final RuntimeConfig runtimeConfig;
    private final AiProviderManager aiProvider;

    @Autowired
    public PipelineController(VacancyPipelineService pipelineService,
                               VacancyRepository vacancyRepo,
                               SearchProfileFactory profileFactory,
                               RuntimeConfig runtimeConfig,
                               AiProviderManager aiProvider) {
        this.pipelineService = pipelineService;
        this.vacancyRepo = vacancyRepo;
        this.profileFactory = profileFactory;
        this.runtimeConfig = runtimeConfig;
        this.aiProvider = aiProvider;
    }

    /**
     * All jobs the current user is allowed to trigger — every job for an admin,
     * only their own for a regular user — optionally narrowed to one (person, search).
     */
    private List<SearchJob> jobsFor(String person, String searchName, User currentUser) {
        List<SearchJob> jobs = profileFactory.build();
        if (!currentUser.isAdmin()) {
            jobs = jobs.stream().filter(j -> currentUser.getId().equals(j.userId)).toList();
        }
        if (person == null && searchName == null) return jobs;
        return jobs.stream()
            .filter(j -> person == null || j.personName.equals(person))
            .filter(j -> searchName == null || j.searchName.equals(searchName))
            .toList();
    }

    /**
     * Run the full pipeline (discover → scrape → AI-analyze → notify) for every
     * configured (person, search), or just one if person/searchName are given.
     * POST /api/pipeline/run[?person=Мама&searchName=Рядом с домом]
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Map<String, Object>> runPipeline(
            @RequestParam(name = "person", required = false) String person,
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestAttribute("currentUser") User currentUser) {
        List<SearchJob> jobs = jobsFor(person, searchName, currentUser);
        log.info("Запуск пайплайна для {} поисков", jobs.size());
        try {
            PipelineResult total = new PipelineResult();
            for (SearchJob job : jobs) {
                PipelineResult r = pipelineService.runFullPipeline(job);
                total.collected += r.collected;
                total.newVacancies += r.newVacancies;
                total.analyzed += r.analyzed;
                total.approved += r.approved;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("jobs", jobs.size());
            response.put("collected", total.collected);
            response.put("newVacancies", total.newVacancies);
            response.put("analyzed", total.analyzed);
            response.put("approved", total.approved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Ошибка пайплайна: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Re-analyze all eligible vacancies (not rejected by AI or user), for every
     * configured (person, search) or just one.
     * POST /api/pipeline/reanalyze[?person=...&searchName=...]
     */
     @PostMapping("/pipeline/reanalyze")
     public ResponseEntity<Map<String, Object>> reanalyze(
             @RequestParam(name = "person", required = false) String person,
             @RequestParam(name = "searchName", required = false) String searchName,
             @RequestAttribute("currentUser") User currentUser) {
         List<SearchJob> jobs = jobsFor(person, searchName, currentUser);
         log.info("Запрошена повторная оценка для {} поисков", jobs.size());
         try {
             VacancyPipelineService.ReanalyzeResult total = new VacancyPipelineService.ReanalyzeResult();
             for (SearchJob job : jobs) {
                 VacancyPipelineService.ReanalyzeResult r = pipelineService.reanalyzeJob(job);
                 total.reset += r.reset;
                 total.analyzed += r.analyzed;
                 total.approved += r.approved;
             }
             Map<String, Object> response = new LinkedHashMap<>();
             response.put("status", "ok");
             response.put("reset", total.reset);
             response.put("analyzed", total.analyzed);
             response.put("approved", total.approved);
             return ResponseEntity.ok(response);
         } catch (Exception e) {
             log.error("Ошибка повторной оценки: {}", e.getMessage(), e);
             Map<String, Object> error = new LinkedHashMap<>();
             error.put("status", "error");
             error.put("message", e.getMessage());
             return ResponseEntity.internalServerError().body(error);
         }
     }

     /**
      * Analyze only pending (unassessed) vacancies — no cap, runs until queue empty,
      * for every configured (person, search) or just one.
      * POST /api/pipeline/analyze-pending[?person=...&searchName=...]
      */
     @PostMapping("/pipeline/analyze-pending")
     public ResponseEntity<Map<String, Object>> analyzePending(
             @RequestParam(name = "person", required = false) String person,
             @RequestParam(name = "searchName", required = false) String searchName,
             @RequestAttribute("currentUser") User currentUser) {
         List<SearchJob> jobs = jobsFor(person, searchName, currentUser);
         log.info("Запрошен анализ необработанных для {} поисков", jobs.size());
         try {
             int analyzed = 0;
             for (SearchJob job : jobs) {
                 analyzed += pipelineService.analyzeAllPending(job);
             }
             Map<String, Object> response = new LinkedHashMap<>();
             response.put("status", "ok");
             response.put("analyzed", analyzed);
             response.put("remaining", vacancyRepo.countUnassessed(currentUser.isAdmin() ? null : currentUser.getId()));
             return ResponseEntity.ok(response);
         } catch (Exception e) {
             log.error("Ошибка анализа необработанных: {}", e.getMessage(), e);
             Map<String, Object> error = new LinkedHashMap<>();
             error.put("status", "error");
             error.put("message", e.getMessage());
             return ResponseEntity.internalServerError().body(error);
         }
     }

    /**
     * Get count of vacancies eligible for re-analysis.
     * GET /api/pipeline/reanalyze/count
     */
    @GetMapping("/pipeline/reanalyze/count")
    public ResponseEntity<Map<String, Object>> reanalyzeCount(@RequestAttribute("currentUser") User currentUser) {
        Long scopedUserId = currentUser.isAdmin() ? null : currentUser.getId();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rescanable", vacancyRepo.countRescanable(scopedUserId));
        response.put("total", vacancyRepo.countTotal(scopedUserId));
        return ResponseEntity.ok(response);
    }

    /**
     * List (person, search) jobs the current user can see — all of them for an
     * admin, only their own for a regular user — so the frontend can build a
     * person/search filter without waiting for a newly-added search to have
     * collected at least one vacancy.
     * GET /api/pipeline/jobs
     */
    @GetMapping("/pipeline/jobs")
    public ResponseEntity<List<Map<String, String>>> listJobs(@RequestAttribute("currentUser") User currentUser) {
        List<Map<String, String>> jobs = jobsFor(null, null, currentUser).stream()
            .map(j -> Map.of("person", j.personName, "searchName", j.searchName))
            .toList();
        return ResponseEntity.ok(jobs);
    }

    /**
     * Get pipeline status (pending count, etc).
     * GET /api/pipeline/status
     */
    @GetMapping("/pipeline/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestAttribute("currentUser") User currentUser) {
        Long scopedUserId = currentUser.isAdmin() ? null : currentUser.getId();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("total", vacancyRepo.countTotal(scopedUserId));
        status.put("pending", vacancyRepo.countPending(scopedUserId));
        status.put("byStatus", vacancyRepo.countByStatus(scopedUserId));
        return ResponseEntity.ok(status);
    }

    /**
     * Get AI status (rate limit, cooldown).
     * GET /api/ai/status
     */
    @GetMapping("/ai/status")
    public ResponseEntity<Map<String, Object>> getAiStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean rateLimited = pipelineService.isAiRateLimited();
        long cooldownUntil = pipelineService.getAiCooldownUntil();
        status.put("rateLimited", rateLimited);
        status.put("cooldownUntil", cooldownUntil);
        status.put("provider", aiProvider.getCurrentProviderName());
        status.put("providerState", aiProvider.getStateLabel());
        status.put("providerIndex", aiProvider.getCurrentIndex());
        status.put("providerCount", aiProvider.getProviderCount());
        status.put("hasFallback", aiProvider.hasFallback());
        if (rateLimited && cooldownUntil > 0) {
            status.put("cooldownUntilIso", java.time.Instant.ofEpochMilli(cooldownUntil).toString());
            long remainingMs = cooldownUntil - System.currentTimeMillis();
            status.put("remainingMinutes", remainingMs / 60000);
        }
        return ResponseEntity.ok(status);
    }

    /**
     * POST /api/ai/reset-provider — manually reset to primary
     */
    @PostMapping("/ai/reset-provider")
    public ResponseEntity<Map<String, Object>> resetProvider(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
        aiProvider.reset();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("provider", aiProvider.getCurrentProviderName());
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /ai/force-fallback — manually switch to fallback (Grok)
     */
    @PostMapping("/ai/force-fallback")
    public ResponseEntity<Map<String, Object>> forceFallback(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
        if (aiProvider.hasFallback()) {
            aiProvider.forceFallback();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("provider", aiProvider.getCurrentProviderName());
            return ResponseEntity.ok(resp);
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Fallback AI provider not configured"));
    }

    /**
     * Get notification settings.
     * GET /api/settings/notifications
     */
    @GetMapping("/settings/notifications")
    public ResponseEntity<Map<String, Object>> getNotificationSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", pipelineService.isNotificationsEnabled());
        return ResponseEntity.ok(settings);
    }

    /**
     * Update notification settings.
     * POST /api/settings/notifications  body: {"enabled": false}
     */
    @PostMapping("/settings/notifications")
    public ResponseEntity<Map<String, Object>> setNotificationSettings(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
        Object enabled = body.get("enabled");
        if (enabled instanceof Boolean) {
            pipelineService.setNotificationsEnabled((Boolean) enabled);
            log.info("Уведомления {}", (Boolean) enabled ? "включены" : "отключены");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", pipelineService.isNotificationsEnabled());
        return ResponseEntity.ok(result);
    }
}
