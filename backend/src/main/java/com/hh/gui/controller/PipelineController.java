package com.hh.gui.controller;

import com.hh.gui.ai.AiProviderManager;
import com.hh.gui.config.RuntimeConfig;
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
     * Run full pipeline for a profile.
     * POST /api/pipeline/run?profile=mom
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Map<String, Object>> runPipeline(
            @RequestParam(name = "profile", defaultValue = "mom") String profileName) {
        log.info("Запуск пайплайна для профиля: {}", profileName);

        try {
            VacancyPipelineService.SearchProfile sp = profileFactory.build();
            PipelineResult result = pipelineService.runFullPipeline(sp);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("profile", profileName);
            response.put("collected", result.collected);
            response.put("newVacancies", result.newVacancies);
            response.put("analyzed", result.analyzed);
            response.put("approved", result.approved);
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
     * Re-analyze all eligible vacancies (not rejected by AI or user).
     /**
      * Re-analyze all eligible vacancies (not rejected by AI or user).
      * POST /api/pipeline/reanalyze
      */
     @PostMapping("/pipeline/reanalyze")
     public ResponseEntity<Map<String, Object>> reanalyze() {
         log.info("Запрошена повторная оценка");
         try {
             VacancyPipelineService.SearchProfile sp = profileFactory.build();
             VacancyPipelineService.ReanalyzeResult result = pipelineService.reanalyzeAll(sp);
             Map<String, Object> response = new LinkedHashMap<>();
             response.put("status", "ok");
             response.put("reset", result.reset);
             response.put("analyzed", result.analyzed);
             response.put("approved", result.approved);
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
      * Analyze only pending (unassessed) vacancies — no cap, runs until queue empty.
      * POST /api/pipeline/analyze-pending
      */
     @PostMapping("/pipeline/analyze-pending")
     public ResponseEntity<Map<String, Object>> analyzePending() {
         log.info("Запрошен анализ необработанных");
         try {
             VacancyPipelineService.SearchProfile sp = profileFactory.build();
             int analyzed = pipelineService.analyzeAllPending(sp);
             Map<String, Object> response = new LinkedHashMap<>();
             response.put("status", "ok");
             response.put("analyzed", analyzed);
             response.put("remaining", vacancyRepo.countUnassessed());
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
    public ResponseEntity<Map<String, Object>> reanalyzeCount() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rescanable", vacancyRepo.countRescanable());
        response.put("total", vacancyRepo.countTotal());
        return ResponseEntity.ok(response);
    }

    /**
     * Get pipeline status (pending count, etc).
     * GET /api/pipeline/status
     */
    @GetMapping("/pipeline/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("total", vacancyRepo.countTotal());
        status.put("pending", vacancyRepo.countPending());
        status.put("byStatus", vacancyRepo.countByStatus());
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
    public ResponseEntity<Map<String, Object>> resetProvider() {
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
    public ResponseEntity<Map<String, Object>> forceFallback() {
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
            @RequestBody Map<String, Object> body) {
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
