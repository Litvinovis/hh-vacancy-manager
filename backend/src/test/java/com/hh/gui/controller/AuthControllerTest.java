package com.hh.gui.controller;

import tools.jackson.databind.ObjectMapper;
import com.hh.gui.model.User;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM history");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM vacancies");
        jdbc.update("DELETE FROM searches");
        jdbc.update("DELETE FROM users");

        User user = new User();
        user.setUsername("testuser");
        user.setPasswordHash(authService.hash("correct-password"));
        user.setDisplayName("Test User");
        user.setRole("user");
        user.setActive(true);
        userRepo.save(user);
    }

    @Test
    void protectedEndpoint_noSession_returns401() throws Exception {
        mockMvc.perform(get("/api/vacancies"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "wrong"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "nobody", "password", "whatever"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void login_thenProtectedEndpoint_thenLogout_thenProtectedEndpoint401() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "correct-password"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("testuser"))
            .andReturn();

        var session = loginResult.getRequest().getSession(false);
        assertNotNull(session);

        // The established session should now pass the auth gate on a protected endpoint.
        mockMvc.perform(get("/api/vacancies").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/vacancies").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_wrongOldPassword_returns400() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "correct-password"))))
            .andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(post("/api/auth/change-password").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("oldPassword", "not-the-real-one", "newPassword", "newpass123"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_thenLoginWithNewPassword_works() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "correct-password"))))
            .andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(post("/api/auth/change-password").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("oldPassword", "correct-password", "newPassword", "brand-new-pass"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "brand-new-pass"))))
            .andExpect(status().isOk());
    }

    @Test
    void inactiveUser_cannotLogin() throws Exception {
        User inactive = userRepo.findByUsername("testuser").orElseThrow();
        inactive.setActive(false);
        userRepo.update(inactive);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "correct-password"))))
            .andExpect(status().isUnauthorized());
    }
}
