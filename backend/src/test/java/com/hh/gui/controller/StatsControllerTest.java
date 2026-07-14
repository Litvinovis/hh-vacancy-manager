package com.hh.gui.controller;

import com.hh.gui.model.User;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression coverage for a real production incident: /api/stats (sidebar
 * counts, progress bar) was never scoped by user, so any logged-in non-admin
 * saw totals across every user's vacancies, not just their own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private VacancyRepository vacancyRepo;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userAId;
    private Long userBId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM history");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM vacancies");
        jdbc.update("DELETE FROM searches");
        jdbc.update("DELETE FROM users");

        userAId = createUser("usera", "user").getId();
        userBId = createUser("userb", "user").getId();
        adminId = createUser("theadmin", "admin").getId();

        // 3 vacancies for user A, 1 for user B — an unscoped query would leak all 4 to either user.
        saveVacancy("a-1", userAId);
        saveVacancy("a-2", userAId);
        saveVacancy("a-3", userAId);
        saveVacancy("b-1", userBId);
    }

    private User createUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(authService.hash("pw"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setActive(true);
        return userRepo.save(u);
    }

    private void saveVacancy(String hhId, Long userId) {
        Vacancy v = new Vacancy();
        v.setHhId(hhId);
        v.setUserId(userId);
        v.setPerson("p");
        v.setSearchName("s");
        v.setStatus("new");
        v.setAiVerdict("pending");
        vacancyRepo.save(v);
    }

    private RequestPostProcessor asUser(Long userId) {
        return request -> {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("userId", userId);
            request.setSession(session);
            return request;
        };
    }

    @Test
    void stats_regularUser_onlySeesOwnVacancies() throws Exception {
        mockMvc.perform(get("/api/stats").with(asUser(userAId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void stats_otherRegularUser_onlySeesOwnVacancies() throws Exception {
        mockMvc.perform(get("/api/stats").with(asUser(userBId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void stats_admin_seesEverything() throws Exception {
        mockMvc.perform(get("/api/stats").with(asUser(adminId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void pipelineStatus_regularUser_onlyCountsOwnVacancies() throws Exception {
        mockMvc.perform(get("/api/pipeline/status").with(asUser(userAId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void pipelineStatus_admin_seesEverything() throws Exception {
        mockMvc.perform(get("/api/pipeline/status").with(asUser(adminId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4));
    }
}
