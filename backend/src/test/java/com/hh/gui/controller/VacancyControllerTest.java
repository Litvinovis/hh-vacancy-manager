package com.hh.gui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.model.*;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VacancyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VacancyRepository vacancyRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private Long testAdminId;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM history");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM vacancies");
        jdbc.update("DELETE FROM searches");
        jdbc.update("DELETE FROM users");

        User admin = new User();
        admin.setUsername("test-admin");
        admin.setPasswordHash("unused");
        admin.setDisplayName("Test Admin");
        admin.setRole("admin");
        admin.setActive(true);
        testAdminId = userRepo.save(admin).getId();
    }

    /** All controller tests authenticate as an admin — endpoint auth itself is covered by AuthController/AuthInterceptor tests. */
    private RequestPostProcessor authed() {
        return request -> {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("userId", testAdminId);
            request.setSession(session);
            return request;
        };
    }

    // ── GET /api/vacancies ──

    @Test
    void listVacancies_returnsOk() throws Exception {
        createControllerVacancy("ctrl-001", "new");
        mockMvc.perform(get("/api/vacancies").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_emptyDb() throws Exception {
        mockMvc.perform(get("/api/vacancies").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listVacancies_fraudFilter() throws Exception {
        Vacancy v = new Vacancy();
        v.setHhId("ctrl-fraud");
        v.setTitle("Fraud");
        v.setStatus("new");
        v.setAiVerdict("fraud");
        v.setAiScore(0);
        vacancyRepo.save(v);

        mockMvc.perform(get("/api/vacancies?status=fraud").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_withMinSalary() throws Exception {
        createControllerVacancy("ctrl-sal", "new");
        jdbc.update("UPDATE vacancies SET salary_to=50000 WHERE hh_id='ctrl-sal'");

        mockMvc.perform(get("/api/vacancies?minSalary=40000").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_withMinSalary_filtersOut() throws Exception {
        createControllerVacancy("ctrl-sal2", "new");
        jdbc.update("UPDATE vacancies SET salary_to=30000 WHERE hh_id='ctrl-sal2'");

        mockMvc.perform(get("/api/vacancies?minSalary=40000").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listVacancies_withMinScore() throws Exception {
        createControllerVacancy("ctrl-score", "new");
        jdbc.update("UPDATE vacancies SET ai_score=80 WHERE hh_id='ctrl-score'");

        mockMvc.perform(get("/api/vacancies?minScore=70").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_withSearch() throws Exception {
        createControllerVacancy("ctrl-search", "new");
        jdbc.update("UPDATE vacancies SET title='УникальныйКонсультант' WHERE hh_id='ctrl-search'");

        mockMvc.perform(get("/api/vacancies?search=Уникальный").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_withRemote() throws Exception {
        createControllerVacancy("ctrl-remote", "new");
        jdbc.update("UPDATE vacancies SET is_remote=1 WHERE hh_id='ctrl-remote'");

        mockMvc.perform(get("/api/vacancies?remote=true").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listVacancies_pagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            createControllerVacancy("ctrl-page-" + i, "new");
        }

        mockMvc.perform(get("/api/vacancies?page=1&perPage=2").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.vacancies.length()").value(2));
    }

    @Test
    void listVacancies_sortBySalaryDesc() throws Exception {
        createControllerVacancy("ctrl-sort-1", "new");
        jdbc.update("UPDATE vacancies SET salary_to=100000 WHERE hh_id='ctrl-sort-1'");
        createControllerVacancy("ctrl-sort-2", "new");
        jdbc.update("UPDATE vacancies SET salary_to=50000 WHERE hh_id='ctrl-sort-2'");

        mockMvc.perform(get("/api/vacancies?sort=salary_desc").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vacancies[0].salaryTo").value(100000));
    }

    // ── GET /api/vacancies/{id} ──

    @Test
    void getVacancy_byId() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-get-001", "new");
        mockMvc.perform(get("/api/vacancies/" + v.getId()).with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Vacancy ctrl-get-001"));
    }

    @Test
    void getVacancy_notFound() throws Exception {
        mockMvc.perform(get("/api/vacancies/99999").with(authed()))
            .andExpect(status().isNotFound());
    }

    @Test
    void getVacancy_invalidId() throws Exception {
        // Non-numeric ID should return 400
        mockMvc.perform(get("/api/vacancies/abc").with(authed()))
            .andExpect(status().isBadRequest());
    }

    // ── POST /api/vacancies ──

    @Test
    void createVacancy_returnsOk() throws Exception {
        Vacancy v = new Vacancy();
        v.setHhId("ctrl-post-001");
        v.setTitle("New Vacancy");
        v.setStatus("new");

        mockMvc.perform(post("/api/vacancies").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(v)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("created"));
    }

    @Test
    void createVacancy_emptyBody() throws Exception {
        mockMvc.perform(post("/api/vacancies").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    // ── PUT /api/vacancies/{id} ──

    @Test
    void updateVacancy_returnsOk() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-put-001", "new");
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("Updated");

        mockMvc.perform(put("/api/vacancies/" + v.getId()).with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("updated"));
    }

    @Test
    void updateVacancy_notFound() throws Exception {
        VacancyUpdateRequest req = new VacancyUpdateRequest();
        req.setTitle("Test");
        mockMvc.perform(put("/api/vacancies/99999").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound());
    }

    // ── PUT /api/vacancies/{id}/status ──

    @Test
    void updateStatus_returnsOk() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-stat-001", "new");
        mockMvc.perform(put("/api/vacancies/" + v.getId() + "/status").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"favorite\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("updated"));
    }

    @Test
    void updateStatus_toFraud() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-stat-002", "new");
        mockMvc.perform(put("/api/vacancies/" + v.getId() + "/status").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"fraud\"}"))
            .andExpect(status().isOk());

        // Verify ai_verdict was set to fraud
        var result = jdbc.queryForMap("SELECT ai_verdict FROM vacancies WHERE id=?", v.getId());
        assertEquals("fraud", result.get("ai_verdict").toString());
    }

    @Test
    void updateStatus_missingStatus() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-stat-003", "new");
        mockMvc.perform(put("/api/vacancies/" + v.getId() + "/status").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateStatus_notFound() throws Exception {
        mockMvc.perform(put("/api/vacancies/99999/status").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"new\"}"))
            .andExpect(status().isNotFound());
    }

    // ── POST /api/vacancies/{id}/reset-score ──

    @Test
    void resetScore_returnsOk() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-reset-001", "new");
        jdbc.update("UPDATE vacancies SET ai_score=85, ai_verdict='yes' WHERE id=?", v.getId());

        mockMvc.perform(post("/api/vacancies/" + v.getId() + "/reset-score").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("reset"));
    }

    @Test
    void resetScore_notFound() throws Exception {
        mockMvc.perform(post("/api/vacancies/99999/reset-score").with(authed()))
            .andExpect(status().isNotFound());
    }

    // ── POST /api/vacancies/bulk-status ──

    @Test
    void bulkStatusUpdate_returnsOk() throws Exception {
        Vacancy v1 = createControllerVacancy("ctrl-bulk-001", "new");
        Vacancy v2 = createControllerVacancy("ctrl-bulk-002", "new");

        mockMvc.perform(post("/api/vacancies/bulk-status").with(authed())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + v1.getId() + "," + v2.getId() + "],\"status\":\"rejected\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2));
    }

    // ── DELETE /api/vacancies/{id} ──

    @Test
    void deleteVacancy_returnsOk() throws Exception {
        Vacancy v = createControllerVacancy("ctrl-del-001", "new");
        mockMvc.perform(delete("/api/vacancies/" + v.getId()).with(authed()))
            .andExpect(status().isOk());
    }

    @Test
    void deleteVacancy_notFound() throws Exception {
        mockMvc.perform(delete("/api/vacancies/99999").with(authed()))
            .andExpect(status().isNotFound());
    }

    // ── GET /api/stats ──

    @Test
    void getStats_returnsOk() throws Exception {
        createControllerVacancy("ctrl-stats-001", "new");
        mockMvc.perform(get("/api/stats").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getStats_includesFraud() throws Exception {
        Vacancy v = new Vacancy();
        v.setHhId("ctrl-stats-fraud");
        v.setTitle("Fraud");
        v.setStatus("new");
        v.setAiVerdict("fraud");
        v.setAiScore(0);
        vacancyRepo.save(v);

        mockMvc.perform(get("/api/stats").with(authed()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.byStatus.fraud").value(1));
    }

    // ── Edge cases ──

    @Test
    void listVacancies_negativePage() throws Exception {
        mockMvc.perform(get("/api/vacancies?page=-1").with(authed()))
            .andExpect(status().isOk());
    }

    @Test
    void listVacancies_zeroPerPage() throws Exception {
        mockMvc.perform(get("/api/vacancies?perPage=0").with(authed()))
            .andExpect(status().isOk());
    }

    @Test
    void listVacancies_hugePerPage() throws Exception {
        mockMvc.perform(get("/api/vacancies?perPage=100000").with(authed()))
            .andExpect(status().isOk());
    }

    @Test
    void listVacancies_invalidSort() throws Exception {
        // Invalid sort should use default
        mockMvc.perform(get("/api/vacancies?sort=invalid_sort").with(authed()))
            .andExpect(status().isOk());
    }

    // ── Helper ──

    private Vacancy createControllerVacancy(String hhId, String status) {
        Vacancy v = new Vacancy();
        v.setHhId(hhId);
        v.setPerson("test-person");
        v.setSearchName("test-search");
        v.setTitle("Vacancy " + hhId);
        v.setCompany("Test");
        v.setStatus(status);
        v.setAiVerdict(status.equals("rejected") ? "no" : "pending");
        v.setAiScore(0);
        return vacancyRepo.save(v);
    }
}
