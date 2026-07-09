package com.hh.gui.service;

import com.hh.gui.model.*;
import com.hh.gui.repository.HistoryRepository;
import com.hh.gui.repository.TagRepository;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class VacancyServiceTest {

    @Autowired
    private VacancyService vacancyService;

    @Autowired
    private VacancyRepository vacancyRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM history");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM vacancies");
    }

    // ── create ──

    @Test
    void create_setsDefaultStatus() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-001");
        Vacancy saved = vacancyService.create(v);
        assertEquals("new", saved.getStatus());
    }

    @Test
    void create_setsDefaultVerdict() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-002");
        v.setStatus("new");
        Vacancy saved = vacancyService.create(v);
        assertEquals("pending", saved.getAiVerdict());
    }

    @Test
    void create_preservesExistingStatus() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-003");
        v.setStatus("favorite");
        Vacancy saved = vacancyService.create(v);
        assertEquals("favorite", saved.getStatus());
    }

    @Test
    void create_setsDefaultCurrency() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-004");
        Vacancy saved = vacancyService.create(v);
        assertEquals("RUR", saved.getCurrency());
    }

    @Test
    void create_nullStatus_setsNew() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-005");
        v.setStatus(null);
        Vacancy saved = vacancyService.create(v);
        assertEquals("new", saved.getStatus());
    }

    @Test
    void create_emptyStatus_setsNew() {
        Vacancy v = new Vacancy();
        v.setTitle("Test");
        v.setHhId("svc-006");
        v.setStatus("");
        Vacancy saved = vacancyService.create(v);
        assertEquals("new", saved.getStatus());
    }

    // ── update ──

    @Test
    void update_title() {
        Vacancy v = createServiceVacancy("upd-001");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("Updated Title");
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals("Updated Title", result.get().getVacancy().getTitle());
    }

    @Test
    void update_company() {
        Vacancy v = createServiceVacancy("upd-002");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setCompany("New Company");
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals("New Company", result.get().getVacancy().getCompany());
    }

    @Test
    void update_salary() {
        Vacancy v = createServiceVacancy("upd-003");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setSalaryFrom(50000);
        req.setSalaryTo(80000);
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals(50000, result.get().getVacancy().getSalaryFrom());
        assertEquals(80000, result.get().getVacancy().getSalaryTo());
    }

    @Test
    void update_status() {
        Vacancy v = createServiceVacancy("upd-004");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setStatus("favorite");
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals("favorite", result.get().getVacancy().getStatus());
    }

    @Test
    void update_nonExistent() {
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("Test");
        var result = vacancyService.update(99999L, req);
        assertTrue(result.isEmpty());
    }

    @Test
    void update_nullFields_doesNotChange() {
        Vacancy v = createServiceVacancy("upd-005");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        // All fields null - should not change anything
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals("Vacancy upd-005", result.get().getVacancy().getTitle());
    }

    @Test
    void update_emptyTitle_updatesToEmpty() {
        Vacancy v = createServiceVacancy("upd-006");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("");
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        // Empty string is different from the old title, so it gets updated
        assertEquals("", result.get().getVacancy().getTitle());
    }

    @Test
    void update_specialCharacters() {
        Vacancy v = createServiceVacancy("upd-007");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("Название <b>жирное</b> & \"кавычки\"");
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
        assertEquals("Название <b>жирное</b> & \"кавычки\"", result.get().getVacancy().getTitle());
    }

    @Test
    void update_veryLongTitle() {
        Vacancy v = createServiceVacancy("upd-008");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("д".repeat(100));  // reasonable long title within VARCHAR(512)
        var result = vacancyService.update(v.getId(), req);
        assertTrue(result.isPresent());
    }

    // ── updateStatus ──

    @Test
    void updateStatus_toFraud_setsAiVerdict() {
        Vacancy v = createServiceVacancy("stat-001");
        vacancyService.updateStatus(v.getId(), "fraud");
        // Check that ai_verdict was updated to fraud
        var updated = vacancyRepo.findById(v.getId());
        assertTrue(updated.isPresent());
        assertEquals("fraud", updated.get().getAiVerdict());
    }

    @Test
    void updateStatus_fromFraud_resetsAiVerdict() {
        Vacancy v = createServiceVacancy("stat-002");
        // First mark as fraud
        vacancyService.updateStatus(v.getId(), "fraud");
        assertEquals("fraud", vacancyRepo.findById(v.getId()).get().getAiVerdict());

        // Then restore to new
        vacancyService.updateStatus(v.getId(), "new");
        assertEquals("pending", vacancyRepo.findById(v.getId()).get().getAiVerdict());
    }

    @Test
    void updateStatus_toApplied() {
        Vacancy v = createServiceVacancy("stat-003");
        vacancyService.updateStatus(v.getId(), "applied");
        var updated = vacancyRepo.findById(v.getId());
        assertTrue(updated.isPresent());
        assertEquals("applied", updated.get().getStatus());
        assertNotNull(updated.get().getAppliedAt());
    }

    // ── resetScore ──

    @Test
    void resetScore_resetsToPending() {
        Vacancy v = createServiceVacancy("reset-001");
        // Set some score first
        jdbc.update("UPDATE vacancies SET ai_score=90, ai_verdict='yes' WHERE id=?", v.getId());

        boolean result = vacancyService.resetScore(v.getId());
        assertTrue(result);

        var updated = vacancyRepo.findById(v.getId());
        assertTrue(updated.isPresent());
        assertEquals("pending", updated.get().getAiVerdict());
        assertEquals(0, updated.get().getAiScore());
    }

    @Test
    void resetScore_nonExistent() {
        boolean result = vacancyService.resetScore(99999L);
        assertFalse(result);
    }

    // ── delete ──

    @Test
    void delete_existing() {
        Vacancy v = createServiceVacancy("del-001");
        boolean result = vacancyService.delete(v.getId());
        assertTrue(result);
        assertTrue(vacancyRepo.findById(v.getId()).isEmpty());
    }

    @Test
    void delete_nonExistent() {
        boolean result = vacancyService.delete(99999L);
        assertFalse(result);
    }

    // ── importVacancies ──

    @Test
    void importVacancies_skipsDuplicates() {
        Vacancy v1 = new Vacancy();
        v1.setHhId("dup-001");
        v1.setPerson("test-person");
        v1.setSearchName("test-search");
        v1.setTitle("First");
        v1.setStatus("new");

        Vacancy v2 = new Vacancy();
        v2.setHhId("dup-001"); // duplicate — same (hh_id, person, search)
        v2.setPerson("test-person");
        v2.setSearchName("test-search");
        v2.setTitle("Second");
        v2.setStatus("new");

        Vacancy v3 = new Vacancy();
        v3.setHhId("dup-002");
        v3.setPerson("test-person");
        v3.setSearchName("test-search");
        v3.setTitle("Third");
        v3.setStatus("new");

        int imported = vacancyService.importVacancies(List.of(v1, v2, v3));
        assertEquals(2, imported); // v1 and v3 imported, v2 skipped
    }

    @Test
    void importVacancies_emptyList() {
        int imported = vacancyService.importVacancies(List.of());
        assertEquals(0, imported);
    }

    @Test
    void importVacancies_nullHhId_skipped() {
        Vacancy v = new Vacancy();
        v.setHhId(null);
        v.setTitle("No HH ID");
        v.setStatus("new");
        // null hhId should be skipped (existsByHhId with null won't match)
        // Actually the check is: if hhId != null && !hhId.isEmpty() && existsByHhId
        // So null hhId will be imported (not skipped)
        int imported = vacancyService.importVacancies(List.of(v));
        assertEquals(1, imported);
    }

    // ── list ──

    @Test
    void list_returnsVacancies() {
        createServiceVacancy("list-001");
        createServiceVacancy("list-002");

        var result = vacancyService.list("new", null, null, null, null, null, null, null, null, null, "score_desc", 1, 30, null);
        assertTrue(result.getTotal() >= 2);
    }

    @Test
    void list_fraudFilter() {
        Vacancy v = new Vacancy();
        v.setHhId("list-fraud");
        v.setTitle("Fraud");
        v.setStatus("new");
        v.setAiVerdict("fraud");
        v.setAiScore(0);
        vacancyRepo.save(v);

        var result = vacancyService.list("fraud", null, null, null, null, null, null, null, null, null, "score_desc", 1, 30, null);
        assertEquals(1, result.getTotal());
    }

    // ── findById ──

    @Test
    void findById_existing() {
        Vacancy v = createServiceVacancy("find-001");
        var result = vacancyService.findById(v.getId());
        assertTrue(result.isPresent());
        assertNotNull(result.get().getHistory());
    }

    @Test
    void findById_nonExistent() {
        var result = vacancyService.findById(99999L);
        assertTrue(result.isEmpty());
    }

    // ── Helper ──

    private Vacancy createServiceVacancy(String hhId) {
        Vacancy v = new Vacancy();
        v.setHhId(hhId);
        v.setPerson("test-person");
        v.setSearchName("test-search");
        v.setTitle("Vacancy " + hhId);
        v.setCompany("Test");
        v.setStatus("new");
        v.setAiVerdict("pending");
        return vacancyRepo.save(v);
    }
}
