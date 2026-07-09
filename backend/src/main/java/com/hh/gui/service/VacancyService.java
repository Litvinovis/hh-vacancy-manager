package com.hh.gui.service;

import com.hh.gui.model.*;
import com.hh.gui.repository.HistoryRepository;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.TagRepository;
import com.hh.gui.repository.UserVacancyStatusRepository;
import com.hh.gui.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class VacancyService {

    private static final Logger log = LoggerFactory.getLogger(VacancyService.class);

    private final VacancyRepository vacancyRepo;
    private final TagRepository tagRepo;
    private final HistoryRepository historyRepo;
    private final UserVacancyStatusRepository userVacancyStatusRepo;
    private final SearchRepository searchRepo;

    @Autowired
    public VacancyService(VacancyRepository vacancyRepo, TagRepository tagRepo,
                          HistoryRepository historyRepo, UserVacancyStatusRepository userVacancyStatusRepo,
                          SearchRepository searchRepo) {
        this.vacancyRepo = vacancyRepo;
        this.tagRepo = tagRepo;
        this.historyRepo = historyRepo;
        this.userVacancyStatusRepo = userVacancyStatusRepo;
        this.searchRepo = searchRepo;
    }

    /**
     * @param viewerId the logged-in user viewing this page (even for an admin, who passes
     *                 null as the scoping userId) — used only to overlay each viewer's own
     *                 status/rejectionReason/appliedAt onto shared (global-search) vacancies.
     *                 A vacancy from a personal search never has an overlay row, so it's
     *                 always returned exactly as stored.
     */
    public PageResponse<VacancyWithTags> list(String status, String district, Integer minSalary,
                                               Integer minScore, String search, String tag,
                                               Boolean remote, String person, String searchName, Long userId,
                                               String sort, int page, int perPage, Long viewerId) {
        int offset = (page - 1) * perPage;
        List<Vacancy> vacancies = vacancyRepo.findAll(status, district, minSalary, minScore,
            search, tag, remote, person, searchName, userId, sort, offset, perPage);
        int total = vacancyRepo.countAll(status, district, minSalary, minScore, search, tag, remote, person, searchName, userId);

        Map<Long, UserVacancyStatus> overlays = viewerId != null
            ? userVacancyStatusRepo.findByUserAndVacancyIds(viewerId, vacancies.stream().map(Vacancy::getId).toList())
            : Map.of();

        List<VacancyWithTags> items = new ArrayList<>();
        for (Vacancy v : vacancies) {
            applyOverlay(v, overlays.get(v.getId()));
            List<String> tags = tagRepo.findNamesByVacancyId(v.getId());
            items.add(new VacancyWithTags(v, tags));
        }

        return new PageResponse<>(total, page, perPage, items);
    }

    /** Single-vacancy counterpart of list()'s bulk overlay — used by the detail endpoint. */
    public void applyViewerOverlay(Vacancy v, Long viewerId) {
        if (viewerId == null) return;
        applyOverlay(v, userVacancyStatusRepo.findOne(viewerId, v.getId()).orElse(null));
    }

    private void applyOverlay(Vacancy v, UserVacancyStatus overlay) {
        if (overlay == null) return;
        v.setStatus(overlay.getStatus());
        v.setRejectionReason(overlay.getRejectionReason());
        v.setAppliedAt(overlay.getAppliedAt());
    }

    /**
     * True if this vacancy came from a shared (is_global) search — its status is per-viewer,
     * not on the row itself. Checked via the owning search, not just "user_id is null":
     * legacy pre-multi-user rows can also have a null user_id without being global.
     */
    public boolean isGlobalVacancy(Long vacancyId) {
        return vacancyRepo.findById(vacancyId)
            .flatMap(v -> v.getSearchId() != null ? searchRepo.findById(v.getSearchId()) : Optional.empty())
            .map(SearchConfig::isGlobal)
            .orElse(false);
    }

    @Transactional
    public boolean updatePersonalStatus(Long vacancyId, Long viewerId, String status) {
        if (vacancyRepo.findById(vacancyId).isEmpty()) return false;
        String appliedAt = "applied".equals(status) ? Instant.now().toString() : "";
        String rejectionReason = userVacancyStatusRepo.findOne(viewerId, vacancyId)
            .map(UserVacancyStatus::getRejectionReason).orElse("");
        userVacancyStatusRepo.upsertStatus(viewerId, vacancyId, status, rejectionReason, appliedAt);
        return true;
    }

    public Optional<VacancyDetail> findById(Long id) {
        Optional<Vacancy> vOpt = vacancyRepo.findById(id);
        if (vOpt.isEmpty()) return Optional.empty();

        Vacancy v = vOpt.get();
        List<String> tags = tagRepo.findNamesByVacancyId(id);
        List<History> history = historyRepo.findByVacancyId(id, 50);

        return Optional.of(new VacancyDetail(v, tags, history));
    }

    @Transactional
    public Vacancy create(Vacancy v) {
        if (v.getStatus() == null || v.getStatus().isEmpty()) {
            v.setStatus("new");
        }
        if (v.getAiVerdict() == null || v.getAiVerdict().isEmpty()) {
            v.setAiVerdict("pending");
        }
        if (v.getCurrency() == null || v.getCurrency().isEmpty()) {
            v.setCurrency("RUR");
        }

        Vacancy saved = vacancyRepo.save(v);
        historyRepo.save(saved.getId(), "created", "Vacancy imported");
        log.info("Создана вакансия: id={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    @Transactional
    public Optional<VacancyWithTags> update(Long id, VacancyUpdateRequest req) {
        Optional<Vacancy> vOpt = vacancyRepo.findById(id);
        if (vOpt.isEmpty()) return Optional.empty();

        Vacancy v = vOpt.get();
        List<String> changes = new ArrayList<>();

        if (req.getTitle() != null && !req.getTitle().equals(v.getTitle())) {
            changes.add("title: " + v.getTitle() + " → " + req.getTitle());
            v.setTitle(req.getTitle());
        }
        if (req.getCompany() != null && !req.getCompany().equals(v.getCompany())) {
            changes.add("company: " + v.getCompany() + " → " + req.getCompany());
            v.setCompany(req.getCompany());
        }
        if (req.getSalaryFrom() != null && !req.getSalaryFrom().equals(v.getSalaryFrom())) {
            v.setSalaryFrom(req.getSalaryFrom());
        }
        if (req.getSalaryTo() != null && !req.getSalaryTo().equals(v.getSalaryTo())) {
            v.setSalaryTo(req.getSalaryTo());
        }
        if (req.getCurrency() != null) v.setCurrency(req.getCurrency());
        if (req.getAddress() != null) v.setAddress(req.getAddress());
        if (req.getDistrict() != null) v.setDistrict(req.getDistrict());
        if (req.getUrl() != null) v.setUrl(req.getUrl());
        if (req.getAiScore() != null) v.setAiScore(req.getAiScore());
        if (req.getDescription() != null) v.setDescription(req.getDescription());
        if (req.getRejectionReason() != null) v.setRejectionReason(req.getRejectionReason());
        if (req.getNotes() != null && !req.getNotes().equals(v.getNotes())) {
            changes.add("notes updated");
            v.setNotes(req.getNotes());
        }

        if (req.getStatus() != null && !req.getStatus().equals(v.getStatus())) {
            changes.add("status: " + v.getStatus() + " → " + req.getStatus());
            v.setStatus(req.getStatus());
        }

        vacancyRepo.update(v);

        // Tags
        if (req.getTags() != null) {
            tagRepo.deleteByVacancyId(id);
            List<String> cleanTags = req.getTags().stream()
                .map(String::trim).filter(t -> !t.isEmpty()).toList();
            if (!cleanTags.isEmpty()) {
                tagRepo.saveAll(id, cleanTags);
            }
            changes.add("tags updated: " + String.join(", ", cleanTags));
        }

        if (!changes.isEmpty()) {
            historyRepo.save(id, "updated", String.join("; ", changes));
        }

        List<String> tags = tagRepo.findNamesByVacancyId(id);
        return Optional.of(new VacancyWithTags(v, tags));
    }

    @Transactional
    public boolean resetScore(Long id) {
        if (vacancyRepo.findById(id).isEmpty()) return false;
        vacancyRepo.resetScore(id);
        historyRepo.save(id, "score_reset", "Score reset to pending for re-analysis");
        return true;
    }

    @Transactional
    public boolean updateStatus(Long id, String status) {
        Optional<Vacancy> vOpt = vacancyRepo.findById(id);
        if (vOpt.isEmpty()) return false;

        Vacancy v = vOpt.get();
        // When moving to/from fraud, also update ai_verdict for consistency
        if ("fraud".equals(status)) {
            vacancyRepo.updateAiResult(v.getHhId(), v.getPerson(), v.getSearchName(), 0, "fraud", "Помечена как обман вручную");
        } else {
            // When restoring from fraud, reset to pending if it was fraud
            if ("fraud".equals(v.getAiVerdict())) {
                vacancyRepo.updateAiResult(v.getHhId(), v.getPerson(), v.getSearchName(), 0, "pending", "Восстановлена из обмана");
            }
        }
        vacancyRepo.updateStatus(id, status);
        historyRepo.save(id, "status_changed", "status → " + status);
        return true;
    }

    @Transactional
    public int updateStatusBulk(BulkStatusRequest req) {
        vacancyRepo.updateStatusBulk(req.getIds(), req.getStatus());
        historyRepo.saveAll(req.getIds(), "bulk_update", "status → " + req.getStatus());
        return req.getIds().size();
    }

    @Transactional
    public boolean delete(Long id) {
        if (vacancyRepo.findById(id).isEmpty()) return false;
        vacancyRepo.deleteById(id);
        return true;
    }

    public void addTag(Long id, String tag) {
        tagRepo.save(id, tag);
        historyRepo.save(id, "tag_added", tag);
    }

    @Transactional
    public int importVacancies(List<Vacancy> vacancies) {
        int imported = 0;
        int skipped = 0;
        for (Vacancy v : vacancies) {
            if (v.getHhId() != null && !v.getHhId().isEmpty()
                    && vacancyRepo.existsByHhIdPersonSearch(v.getHhId(), v.getPerson(), v.getSearchName())) {
                skipped++;
                continue;
            }
            create(v);
            imported++;
        }
        log.info("Импорт завершён: импортировано={}, пропущено={}", imported, skipped);
        return imported;
    }

    // ── Inner DTOs ──

    public static class VacancyWithTags {
        private final Vacancy vacancy;
        private final List<String> tags;

        public VacancyWithTags(Vacancy vacancy, List<String> tags) {
            this.vacancy = vacancy;
            this.tags = tags;
        }

        public Vacancy getVacancy() { return vacancy; }
        public List<String> getTags() { return tags; }
    }

    public static class VacancyDetail {
        private final Vacancy vacancy;
        private final List<String> tags;
        private final List<History> history;

        public VacancyDetail(Vacancy vacancy, List<String> tags, List<History> history) {
            this.vacancy = vacancy;
            this.tags = tags;
            this.history = history;
        }

        public Vacancy getVacancy() { return vacancy; }
        public List<String> getTags() { return tags; }
        public List<History> getHistory() { return history; }
    }
}
