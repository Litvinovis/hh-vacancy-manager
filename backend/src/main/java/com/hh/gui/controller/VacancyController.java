package com.hh.gui.controller;

import com.hh.gui.model.*;
import com.hh.gui.service.VacancyService;
import com.hh.gui.service.VacancyService.VacancyDetail;
import com.hh.gui.service.VacancyService.VacancyWithTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class VacancyController {

    private static final Logger log = LoggerFactory.getLogger(VacancyController.class);

    private final VacancyService vacancyService;

    @Autowired
    public VacancyController(VacancyService vacancyService) {
        this.vacancyService = vacancyService;
    }

    @GetMapping("/vacancies")
    public Map<String, Object> listVacancies(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "district", required = false) String district,
            @RequestParam(name = "minSalary", required = false) Integer minSalary,
            @RequestParam(name = "minScore", required = false) Integer minScore,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "sort", defaultValue = "score_desc") String sort,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "perPage", defaultValue = "30") int perPage) {

        PageResponse<VacancyWithTags> resp = vacancyService.list(
            status, district, minSalary, minScore, search, tag, sort, page, perPage);

        List<VacancyWithTags> items = resp.getItems();

        // Flatten for JSON response
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", resp.getTotal());
        result.put("page", resp.getPage());
        result.put("perPage", resp.getPerPage());
        result.put("vacancies", items.stream().map(vwt -> {
            Vacancy v = vwt.getVacancy();
            Map<String, Object> vm = new java.util.LinkedHashMap<>();
            vm.put("id", v.getId());
            vm.put("hhId", v.getHhId() != null ? v.getHhId() : "");
            vm.put("title", v.getTitle() != null ? v.getTitle() : "");
            vm.put("company", v.getCompany() != null ? v.getCompany() : "");
            vm.put("salaryFrom", v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
            vm.put("salaryTo", v.getSalaryTo() != null ? v.getSalaryTo() : 0);
            vm.put("currency", v.getCurrency() != null ? v.getCurrency() : "RUR");
            vm.put("address", v.getAddress() != null ? v.getAddress() : "");
            vm.put("district", v.getDistrict() != null ? v.getDistrict() : "");
            vm.put("url", v.getUrl() != null ? v.getUrl() : "");
            vm.put("aiScore", v.getAiScore() != null ? v.getAiScore() : 0);
            vm.put("description", v.getDescription() != null ? v.getDescription() : "");
            vm.put("status", v.getStatus() != null ? v.getStatus() : "new");
            vm.put("notes", v.getNotes() != null ? v.getNotes() : "");
            vm.put("appliedAt", v.getAppliedAt() != null ? v.getAppliedAt() : "");
            vm.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt() : "");
            vm.put("updatedAt", v.getUpdatedAt() != null ? v.getUpdatedAt() : "");
            vm.put("tags", vwt.getTags());
            return vm;
        }).toList());
        return result;
    }

    @GetMapping("/vacancies/{id}")
    public ResponseEntity<?> getVacancy(@PathVariable Long id) {
        Optional<VacancyDetail> detail = vacancyService.findById(id);
        if (detail.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        VacancyDetail d = detail.get();
        Vacancy v = d.getVacancy();

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", v.getId());
        response.put("hhId", v.getHhId() != null ? v.getHhId() : "");
        response.put("title", v.getTitle() != null ? v.getTitle() : "");
        response.put("company", v.getCompany() != null ? v.getCompany() : "");
        response.put("salaryFrom", v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
        response.put("salaryTo", v.getSalaryTo() != null ? v.getSalaryTo() : 0);
        response.put("currency", v.getCurrency() != null ? v.getCurrency() : "RUR");
        response.put("address", v.getAddress() != null ? v.getAddress() : "");
        response.put("district", v.getDistrict() != null ? v.getDistrict() : "");
        response.put("url", v.getUrl() != null ? v.getUrl() : "");
        response.put("aiScore", v.getAiScore() != null ? v.getAiScore() : 0);
        response.put("aiVerdict", v.getAiVerdict() != null ? v.getAiVerdict() : "pending");
        response.put("aiReason", v.getAiReason() != null ? v.getAiReason() : "");
        response.put("description", v.getDescription() != null ? v.getDescription() : "");
        response.put("status", v.getStatus() != null ? v.getStatus() : "new");
        response.put("rejectionReason", v.getRejectionReason() != null ? v.getRejectionReason() : "");
        response.put("notes", v.getNotes() != null ? v.getNotes() : "");
        response.put("appliedAt", v.getAppliedAt() != null ? v.getAppliedAt() : "");
        response.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt() : "");
        response.put("updatedAt", v.getUpdatedAt() != null ? v.getUpdatedAt() : "");
        response.put("tags", d.getTags());
        response.put("history", d.getHistory().stream().map(h -> {
            Map<String, Object> hm = new java.util.LinkedHashMap<>();
            hm.put("id", h.getId());
            hm.put("action", h.getAction());
            hm.put("details", h.getDetails() != null ? h.getDetails() : "");
            hm.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt() : "");
            return hm;
        }).toList());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/vacancies")
    public ResponseEntity<?> createVacancy(@RequestBody Vacancy vacancy) {
        Vacancy saved = vacancyService.create(vacancy);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "status", "created"));
    }

    @PutMapping("/vacancies/{id}")
    public ResponseEntity<?> updateVacancy(@PathVariable Long id,
                                            @RequestBody VacancyUpdateRequest req) {
        Optional<VacancyWithTags> result = vacancyService.update(id, req);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Vacancy v = result.get().getVacancy();
        return ResponseEntity.ok(Map.of("status", "updated", "id", v.getId()));
    }

    @PostMapping("/vacancies/bulk-status")
    public ResponseEntity<?> bulkStatusUpdate(@RequestBody BulkStatusRequest req) {
        int count = vacancyService.updateStatusBulk(req);
        return ResponseEntity.ok(Map.of("status", "ok", "count", count));
    }

    @DeleteMapping("/vacancies/{id}")
    public ResponseEntity<?> deleteVacancy(@PathVariable Long id) {
        boolean deleted = vacancyService.delete(id);
        if (!deleted) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/import")
    public ResponseEntity<?> importVacancies(@RequestBody List<Vacancy> vacancies) {
        int imported = vacancyService.importVacancies(vacancies);
        int skipped = vacancies.size() - imported;
        return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped));
    }
}
