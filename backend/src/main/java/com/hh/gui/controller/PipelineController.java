package com.hh.gui.controller;

import com.hh.gui.ai.AiProviderManager;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.User;
import com.hh.gui.service.LegacyImportService;
import com.hh.gui.service.PipelineJobRunner;
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
    private final PipelineJobRunner jobRunner;
    private final LegacyImportService legacyImportService;

    @Autowired
    public PipelineController(VacancyPipelineService pipelineService,
                               VacancyRepository vacancyRepo,
                               SearchProfileFactory profileFactory,
                               RuntimeConfig runtimeConfig,
                               AiProviderManager aiProvider,
                               PipelineJobRunner jobRunner,
                               LegacyImportService legacyImportService) {
        this.pipelineService = pipelineService;
        this.vacancyRepo = vacancyRepo;
        this.profileFactory = profileFactory;
        this.runtimeConfig = runtimeConfig;
        this.aiProvider = aiProvider;
        this.jobRunner = jobRunner;
        this.legacyImportService = legacyImportService;
    }

    /**
     * All jobs the current user may VIEW — every job for an admin, their own plus every
     * global (shared) job for a regular user — optionally narrowed to one (person, search).
     * Read-only listing is safe to include global jobs for everyone; actually TRIGGERING
     * one is a stricter set (see triggerableJobsFor below) since a regular user manually
     * re-running a global search would affect what every other user sees.
     */
    private List<SearchJob> jobsFor(String person, String searchName, User currentUser) {
        List<SearchJob> jobs = profileFactory.build();
        if (!currentUser.isAdmin()) {
            jobs = jobs.stream().filter(j -> currentUser.getId().equals(j.userId) || j.isGlobal).toList();
        }
        if (person == null && searchName == null) return jobs;
        return jobs.stream()
            .filter(j -> person == null || j.personName.equals(person))
            .filter(j -> searchName == null || j.searchName.equals(searchName))
            .toList();
    }

    /** Same job set as jobsFor(null, null, ...), but for manual pipeline-trigger endpoints
     * a regular user should NOT be able to force-run a global search that affects every
     * other user's shared results — those stay admin-only there. Listing (GET /pipeline/jobs)
     * is read-only, so it's safe to include global jobs for everyone. */
    private List<SearchJob> triggerableJobsFor(String person, String searchName, User currentUser) {
        List<SearchJob> jobs = jobsFor(person, searchName, currentUser);
        if (currentUser.isAdmin()) return jobs;
        return jobs.stream().filter(j -> !j.isGlobal).toList();
    }

    /**
     * Start the full pipeline (discover → scrape → AI-analyze → notify) in the
     * BACKGROUND for every configured (person, search), or just one if
     * person/searchName are given. Returns immediately; progress and the final
     * counters come from GET /api/pipeline/run/status — the old synchronous
     * behaviour held the HTTP request open for the whole multi-minute run, and a
     * browser timeout meant the user never saw the result.
     * POST /api/pipeline/run[?person=Мама&searchName=Рядом с домом]
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Map<String, Object>> runPipeline(
            @RequestParam(name = "person", required = false) String person,
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestAttribute("currentUser") User currentUser) {
        List<SearchJob> jobs = triggerableJobsFor(person, searchName, currentUser);
        log.info("Запуск пайплайна для {} поисков", jobs.size());
        boolean started = jobRunner.start(PipelineJobRunner.Type.RUN, jobs, job -> {
            PipelineResult r = pipelineService.runFullPipeline(job);
            Map<String, Integer> c = new LinkedHashMap<>();
            c.put("collected", r.collected);
            c.put("newVacancies", r.newVacancies);
            c.put("analyzed", r.analyzed);
            c.put("approved", r.approved);
            return c;
        });
        return startResponse(started, jobs.size());
    }

    /** Progress/result of the current (or last) manually-triggered background job. */
    @GetMapping("/pipeline/run/status")
    public ResponseEntity<Map<String, Object>> runStatus() {
        return ResponseEntity.ok(jobRunner.status());
    }

    private ResponseEntity<Map<String, Object>> startResponse(boolean started, int jobs) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!started) {
            response.put("status", "already_running");
            return ResponseEntity.status(409).body(response);
        }
        response.put("status", "started");
        response.put("jobs", jobs);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Discover candidates from an hh.ru search-results URL — either the one saved on
     * the search (search.sourceUrl, used automatically by PipelineScheduler too) or a
     * one-off URL passed in the request body, which overrides the saved one for this
     * manual run only (doesn't persist it). Runs the hits through the normal
     * scrape → AI-analyze → notify steps scored against that search's own criteria.
     * POST /api/pipeline/discover-from-url  body: {searchId, url?, maxPages?}
     */
    @PostMapping("/pipeline/discover-from-url")
    public ResponseEntity<Map<String, Object>> discoverFromUrl(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Поиск по ссылке доступен только администратору"));
        }
        Object searchIdRaw = body.get("searchId");
        if (searchIdRaw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Укажите searchId"));
        }
        Long searchId;
        try {
            searchId = Long.valueOf(searchIdRaw.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Некорректный searchId"));
        }
        int maxPages = 3;
        if (body.get("maxPages") instanceof Number n) maxPages = n.intValue();

        Optional<SearchJob> jobOpt = profileFactory.buildForSearchId(searchId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Поиск не найден"));
        }
        SearchJob job = jobOpt.get();
        String bodyUrl = body.get("url") != null ? body.get("url").toString().trim() : null;
        String url = (bodyUrl != null && !bodyUrl.isBlank()) ? bodyUrl : job.sourceUrl;
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "У поиска не задана ссылка — укажите url или сохраните её в поиске"));
        }

        log.info("Запуск поиска по ссылке для {} · {}: {}", job.personName, job.searchName, url);
        try {
            PipelineResult r = pipelineService.runFullPipelineFromUrl(job, url, maxPages);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("collected", r.collected);
            response.put("newVacancies", r.newVacancies);
            response.put("analyzed", r.analyzed);
            response.put("approved", r.approved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Ошибка поиска по ссылке: {}", e.getMessage(), e);
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
         List<SearchJob> jobs = triggerableJobsFor(person, searchName, currentUser);
         log.info("Запрошена повторная оценка для {} поисков", jobs.size());
         boolean started = jobRunner.start(PipelineJobRunner.Type.REANALYZE, jobs, job -> {
             VacancyPipelineService.ReanalyzeResult r = pipelineService.reanalyzeJob(job);
             Map<String, Integer> c = new LinkedHashMap<>();
             c.put("reset", r.reset);
             c.put("analyzed", r.analyzed);
             c.put("approved", r.approved);
             return c;
         });
         return startResponse(started, jobs.size());
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
         List<SearchJob> jobs = triggerableJobsFor(person, searchName, currentUser);
         log.info("Запрошен анализ необработанных для {} поисков", jobs.size());
         boolean started = jobRunner.start(PipelineJobRunner.Type.ANALYZE_PENDING, jobs, job -> {
             int analyzed = pipelineService.analyzeAllPending(job);
             Map<String, Integer> c = new LinkedHashMap<>();
             c.put("analyzed", analyzed);
             return c;
         });
         return startResponse(started, jobs.size());
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
     * POST /api/pipeline/freshness-check — manually run one liveness re-check batch
     * (the same pass the 10-minute schedule runs; see checkVacancyFreshness).
     */
    @PostMapping("/pipeline/freshness-check")
    public ResponseEntity<Map<String, Object>> freshnessCheck(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
        int limit = VacancyPipelineService.FRESHNESS_BATCH_PER_TICK;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = Math.max(1, Math.min(50, n.intValue()));
        }
        VacancyPipelineService.FreshnessResult r = pipelineService.checkVacancyFreshness(limit);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("alive", r.alive);
        resp.put("closed", r.closed);
        resp.put("inconclusive", r.inconclusive);
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/pipeline/import-legacy — manually run one v1-archive import batch
     * (the same pass the nightly schedule runs; see LegacyImportService).
     * Body: {"limit": N, "onlyYes": true|false}
     */
    @PostMapping("/pipeline/import-legacy")
    public ResponseEntity<Map<String, Object>> importLegacy(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
        int limit = LegacyImportService.DAILY_BATCH;
        boolean onlyYes = false;
        if (body != null) {
            if (body.get("limit") instanceof Number n) limit = Math.max(1, Math.min(2000, n.intValue()));
            if (body.get("onlyYes") instanceof Boolean b) onlyYes = b;
        }
        LegacyImportService.ImportResult r = legacyImportService.importBatch(limit, onlyYes);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (r.error != null) resp.put("error", r.error);
        resp.put("considered", r.considered);
        resp.put("alreadyPresent", r.alreadyPresent);
        resp.put("excluded", r.excluded);
        resp.put("prescreenRejected", r.prescreenRejected);
        resp.put("imported", r.imported);
        return ResponseEntity.ok(resp);
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
