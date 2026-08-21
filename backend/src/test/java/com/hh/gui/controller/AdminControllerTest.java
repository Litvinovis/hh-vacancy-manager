package com.hh.gui.controller;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController has no dedicated interceptor layer of its own — every endpoint
 * checks isAdmin() itself — so per-endpoint admin gating plus the "can't delete
 * yourself" and "user not found" branches are the real risk here, not covered
 * anywhere else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private SearchRepository searchRepo;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long adminId;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM history");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM vacancies");
        jdbc.update("DELETE FROM searches");
        jdbc.update("DELETE FROM users");

        adminId = createUser("theadmin", "admin").getId();
        userId = createUser("regular", "user").getId();
    }

    private User createUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(authService.hash("initial-pw"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setActive(true);
        return userRepo.save(u);
    }

    private RequestPostProcessor as(Long id) {
        return request -> {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("userId", id);
            request.setSession(session);
            return request;
        };
    }

    // ── GET /api/admin/users ──

    @Test
    void listUsers_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(as(userId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_admin_returnsAllUsersWithoutPasswordHash() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(as(adminId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.username=='regular')].passwordHash").doesNotExist());
    }

    // ── POST /api/admin/users ──

    @Test
    void createUser_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/users").with(as(userId))
                .contentType("application/json").content("{\"username\":\"new1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createUser_missingUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/users").with(as(adminId))
                .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createUser_admin_noPasswordGiven_generatesOne() throws Exception {
        mockMvc.perform(post("/api/admin/users").with(as(adminId))
                .contentType("application/json").content("{\"username\":\"newperson\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("newperson"))
            .andExpect(jsonPath("$.generatedPassword").isNotEmpty());
    }

    @Test
    void createUser_admin_duplicateUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/users").with(as(adminId))
                .contentType("application/json").content("{\"username\":\"regular\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── PUT /api/admin/users/{id} ──

    @Test
    void updateUser_nonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + userId).with(as(userId))
                .contentType("application/json").content("{\"displayName\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_admin_updatesDisplayNameAndRole() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + userId).with(as(adminId))
                .contentType("application/json").content("{\"displayName\":\"Новое имя\",\"role\":\"admin\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Новое имя"))
            .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void updateUser_admin_nonexistentId_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/users/999999").with(as(adminId))
                .contentType("application/json").content("{\"displayName\":\"x\"}"))
            .andExpect(status().isNotFound());
    }

    // ── POST /api/admin/users/{id}/reset-password ──

    @Test
    void resetPassword_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + userId + "/reset-password").with(as(userId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void resetPassword_admin_returnsNewPasswordThatActuallyMatchesStoredHash() throws Exception {
        String body = mockMvc.perform(post("/api/admin/users/" + userId + "/reset-password").with(as(adminId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String newPassword = body.replaceAll(".*\"generatedPassword\":\"([^\"]+)\".*", "$1");
        User reloaded = userRepo.findById(userId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(authService.matches(newPassword, reloaded.getPasswordHash()));
    }

    @Test
    void resetPassword_admin_nonexistentId_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/users/999999/reset-password").with(as(adminId)))
            .andExpect(status().isNotFound());
    }

    // ── DELETE /api/admin/users/{id} ──

    @Test
    void deleteUser_nonAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/api/admin/users/" + adminId).with(as(userId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_admin_cannotDeleteOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/admin/users/" + adminId).with(as(adminId)))
            .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertTrue(userRepo.findById(adminId).isPresent());
    }

    @Test
    void deleteUser_admin_deletesOtherUserAndTheirSearches() throws Exception {
        SearchConfig s = new SearchConfig();
        s.setUserId(userId);
        s.setName("test-search");
        s.setQueries(List.of("java"));
        s.setArea(1);
        searchRepo.save(s);

        mockMvc.perform(delete("/api/admin/users/" + userId).with(as(adminId)))
            .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertTrue(userRepo.findById(userId).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(searchRepo.findByUserId(userId).isEmpty());
    }

    @Test
    void deleteUser_admin_nonexistentId_returns404() throws Exception {
        mockMvc.perform(delete("/api/admin/users/999999").with(as(adminId)))
            .andExpect(status().isNotFound());
    }

    // ── GET /api/admin/global-searches ──

    @Test
    void globalSearches_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/global-searches").with(as(userId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void globalSearches_admin_returnsOnlyGlobalOnes() throws Exception {
        SearchConfig personal = new SearchConfig();
        personal.setUserId(userId);
        personal.setName("personal");
        personal.setQueries(List.of("java"));
        personal.setArea(1);
        searchRepo.save(personal);

        SearchConfig global = new SearchConfig();
        global.setUserId(adminId);
        global.setName("shared");
        global.setQueries(List.of("java"));
        global.setArea(1);
        global.setGlobal(true);
        searchRepo.save(global);

        mockMvc.perform(get("/api/admin/global-searches").with(as(adminId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("shared"));
    }

    // ── GET /api/admin/rejection-report ──

    @Test
    void rejectionReport_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/rejection-report").with(as(userId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectionReport_admin_returnsOkWithNoData() throws Exception {
        mockMvc.perform(get("/api/admin/rejection-report").with(as(adminId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }
}
