package com.hh.gui.controller;

import com.hh.gui.service.VacancyPipelineService;
import com.hh.gui.service.VacancyPipelineService.PipelineResult;
import com.hh.gui.service.VacancyPipelineService.SearchProfile;
import com.hh.gui.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for pipeline control and status.
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final VacancyPipelineService pipelineService;
    private final VacancyRepository vacancyRepo;

    @Value("${app.search.profiles-dir}")
    private String profilesDir;

    @Autowired
    public PipelineController(VacancyPipelineService pipelineService, VacancyRepository vacancyRepo) {
        this.pipelineService = pipelineService;
        this.vacancyRepo = vacancyRepo;
    }

    /**
     * Run full pipeline for a profile.
     * POST /api/pipeline/run?profile=mom
     */
    @PostMapping("/pipeline/run")
    public ResponseEntity<Map<String, Object>> runPipeline(
            @RequestParam(name = "profile", defaultValue = "mom") String profile) {
        log.info("Pipeline run requested for profile: {}", profile);

        try {
            SearchProfile sp = loadProfile(profile);
            PipelineResult result = pipelineService.runFullPipeline(sp);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("profile", profile);
            response.put("collected", result.collected);
            response.put("newVacancies", result.newVacancies);
            response.put("analyzed", result.analyzed);
            response.put("approved", result.approved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Pipeline error: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
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

    @SuppressWarnings("unchecked")
    private SearchProfile loadProfile(String profile) {
        // For now, hardcode the mom profile. TODO: load from YAML
        SearchProfile sp = new SearchProfile();
        sp.city = "Уфа";
        sp.priorityDistricts = List.of("Шакша", "Калининский");
        sp.skills = List.of("Работа с клиентами", "Касса", "Консультирование");
        sp.notSuitable = List.of("Физический труд", "Кол-центр", "Вахта", "Склад", "Производство", "Супермаркет");
        sp.salaryMin = 40000;
        sp.schedule = "fullTime";
        sp.area = 99;
        sp.remoteArea = 113;
        sp.localQueries = List.of("продавец", "консультант", "оператор", "администратор", "менеджер");
        sp.remoteQueries = List.of("оператор ПК", "модератор", "администратор интернет-магазин",
            "специалист поддержки", "диспетчер", "помощник руководителя", "секретарь",
            "менеджер маркетплейс", "контент-менеджер", "специалист чат");
        return sp;
    }
}
