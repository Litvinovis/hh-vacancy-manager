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
            @RequestParam(name = "remote", required = false) Boolean remote,
            @RequestParam(name = "person", required = false) String person,
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestParam(name = "sort", defaultValue = "score_desc") String sort,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "perPage", defaultValue = "30") int perPage,
            @RequestAttribute("currentUser") User currentUser) {

        // Clamp to valid values
        if (page < 1) page = 1;
        if (perPage < 1) perPage = 30;
        if (perPage > 200) perPage = 200;

        Long scopedUserId = currentUser.isAdmin() ? null : currentUser.getId();
        PageResponse<VacancyWithTags> resp = vacancyService.list(
            status, district, minSalary, minScore, search, tag, remote, person, searchName, scopedUserId,
            sort, page, perPage, currentUser.getId());

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
            vm.put("person", v.getPerson() != null ? v.getPerson() : "");
            vm.put("searchName", v.getSearchName() != null ? v.getSearchName() : "");
            vm.put("title", v.getTitle() != null ? v.getTitle() : "");
            vm.put("company", v.getCompany() != null ? v.getCompany() : "");
            vm.put("salaryFrom", v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
            vm.put("salaryTo", v.getSalaryTo() != null ? v.getSalaryTo() : 0);
            vm.put("currency", v.getCurrency() != null ? v.getCurrency() : "RUR");
            vm.put("address", v.getAddress() != null ? v.getAddress() : "");
            vm.put("district", v.getDistrict() != null ? v.getDistrict() : "");
            vm.put("url", v.getUrl() != null ? v.getUrl() : "");
            vm.put("aiScore", v.getAiScore() != null ? v.getAiScore() : 0);
            vm.put("aiVerdict", v.getAiVerdict() != null ? v.getAiVerdict() : "pending");
            vm.put("aiReason", v.getAiReason() != null ? v.getAiReason() : "");
            vm.put("description", v.getDescription() != null ? v.getDescription() : "");
            vm.put("status", v.getStatus() != null ? v.getStatus() : "new");
            vm.put("notes", v.getNotes() != null ? v.getNotes() : "");
            vm.put("appliedAt", v.getAppliedAt() != null ? v.getAppliedAt() : "");
            vm.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt() : "");
            vm.put("updatedAt", v.getUpdatedAt() != null ? v.getUpdatedAt() : "");
            vm.put("source", v.getSource() != null ? v.getSource() : "hh");
            vm.put("isRemote", v.isRemote());
            vm.put("notified", v.isNotified());
            vm.put("publishedAt", v.getPublishedAt() != null ? v.getPublishedAt() : "");
            vm.put("tags", vwt.getTags());
            return vm;
        }).toList());
        return result;
    }

    @GetMapping("/vacancies/{id}")
    public ResponseEntity<?> getVacancy(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        Optional<VacancyDetail> detail = vacancyService.findById(id);
        if (detail.isEmpty() || !canAccess(detail.get().getVacancy(), currentUser)) {
            return ResponseEntity.notFound().build();
        }

        VacancyDetail d = detail.get();
        Vacancy v = d.getVacancy();
        vacancyService.applyViewerOverlay(v, currentUser.getId());

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", v.getId());
        response.put("hhId", v.getHhId() != null ? v.getHhId() : "");
        response.put("person", v.getPerson() != null ? v.getPerson() : "");
        response.put("searchName", v.getSearchName() != null ? v.getSearchName() : "");
        response.put("title", v.getTitle() != null ? v.getTitle() : "");
        response.put("company", v.getCompany() != null ? v.getCompany() : "");
        response.put("salaryFrom", v.getSalaryFrom() != null ? v.getSalaryFrom() : 0);
        response.put("salaryTo", v.getSalaryTo() != null ? v.getSalaryTo() : 0);
        response.put("currency", v.getCurrency() != null ? v.getCurrency() : "RUR");
        response.put("address", v.getAddress() != null ? v.getAddress() : "");
        response.put("district", v.getDistrict() != null ? v.getDistrict() : "");
        response.put("url", v.getUrl() != null ? v.getUrl() : "");
        response.put("experience", v.getExperience() != null ? v.getExperience() : "");
        response.put("employment", v.getEmployment() != null ? v.getEmployment() : "");
        response.put("keySkills", v.getKeySkills() != null ? v.getKeySkills() : "");
        response.put("trustedEmployer", v.isTrustedEmployer());
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
        response.put("isRemote", v.isRemote());
        response.put("publishedAt", v.getPublishedAt() != null ? v.getPublishedAt() : "");
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
                                            @RequestBody VacancyUpdateRequest req,
                                            @RequestAttribute("currentUser") User currentUser) {
        if (!ownsVacancy(id, currentUser)) return ResponseEntity.notFound().build();
        // Full-detail editing (title/company/notes/status) on a shared vacancy is a data
        // correction, not a per-user action — restrict to admin; regular users mark their
        // own applied/rejected through PUT /vacancies/{id}/status instead.
        if (!currentUser.isAdmin() && vacancyService.isGlobalVacancy(id)) return forbidden();
        Optional<VacancyWithTags> result = vacancyService.update(id, req);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Vacancy v = result.get().getVacancy();
        return ResponseEntity.ok(Map.of("status", "updated", "id", v.getId()));
    }

    @PostMapping("/vacancies/{id}/tags")
    public ResponseEntity<?> addTag(@PathVariable Long id, @RequestBody Map<String, String> body,
                                     @RequestAttribute("currentUser") User currentUser) {
        String tag = body.get("tag");
        if (tag == null || tag.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tag is required"));
        }
        if (!ownsVacancy(id, currentUser)) return ResponseEntity.notFound().build();
        vacancyService.addTag(id, tag.trim());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/vacancies/{id}/reset-score")
    public ResponseEntity<?> resetScore(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        if (!ownsVacancy(id, currentUser)) return ResponseEntity.notFound().build();
        // Global vacancies are shared — only an admin re-triggers analysis for everyone at once.
        if (!currentUser.isAdmin() && vacancyService.isGlobalVacancy(id)) return forbidden();
        boolean reset = vacancyService.resetScore(id);
        if (!reset) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", "reset", "id", id));
    }

    @PutMapping("/vacancies/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body,
                                           @RequestAttribute("currentUser") User currentUser) {
        String status = body.get("status");
        if (status == null || status.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        if (!ownsVacancy(id, currentUser)) return ResponseEntity.notFound().build();
        // Shared (global-search) vacancy: each user's applied/rejected is their own, not
        // written onto the row everyone else sees — see UserVacancyStatus.
        boolean updated = vacancyService.isGlobalVacancy(id)
            ? vacancyService.updatePersonalStatus(id, currentUser.getId(), status)
            : vacancyService.updateStatus(id, status);
        if (!updated) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", "updated", "id", id));
    }

    @PostMapping("/vacancies/bulk-status")
    public ResponseEntity<?> bulkStatusUpdate(@RequestBody BulkStatusRequest req,
                                               @RequestAttribute("currentUser") User currentUser) {
        List<Long> ownedIds = req.getIds().stream().filter(id -> ownsVacancy(id, currentUser)).toList();
        int count = 0;
        List<Long> personalIds = new java.util.ArrayList<>();
        for (Long id : ownedIds) {
            if (vacancyService.isGlobalVacancy(id)) {
                if (vacancyService.updatePersonalStatus(id, currentUser.getId(), req.getStatus())) count++;
            } else {
                personalIds.add(id);
            }
        }
        if (!personalIds.isEmpty()) {
            BulkStatusRequest scoped = new BulkStatusRequest();
            scoped.setIds(personalIds);
            scoped.setStatus(req.getStatus());
            count += vacancyService.updateStatusBulk(scoped);
        }
        return ResponseEntity.ok(Map.of("status", "ok", "count", count));
    }

    @DeleteMapping("/vacancies/{id}")
    public ResponseEntity<?> deleteVacancy(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        if (!ownsVacancy(id, currentUser)) return ResponseEntity.notFound().build();
        // Global vacancies are shared — only an admin removes one for everyone.
        if (!currentUser.isAdmin() && vacancyService.isGlobalVacancy(id)) return forbidden();
        boolean deleted = vacancyService.delete(id);
        if (!deleted) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Действие доступно только администратору для общей вакансии"));
    }

    /**
     * Non-admins may access vacancies tied to their own user_id, plus every vacancy from
     * a shared (is_global) search — those are visible to everyone. Legacy rows with no
     * owner and no global search behind them are admin-only.
     */
    private boolean canAccess(Vacancy v, User currentUser) {
        if (currentUser.isAdmin()) return true;
        if (v.getUserId() != null && v.getUserId().equals(currentUser.getId())) return true;
        return vacancyService.isGlobalVacancy(v.getId());
    }

    private boolean ownsVacancy(Long id, User currentUser) {
        return vacancyService.findById(id).map(d -> canAccess(d.getVacancy(), currentUser)).orElse(false);
    }

    @PostMapping("/import")
    public ResponseEntity<?> importVacancies(@RequestBody List<Vacancy> vacancies) {
        int imported = vacancyService.importVacancies(vacancies);
        int skipped = vacancies.size() - imported;
        return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped));
    }
}
