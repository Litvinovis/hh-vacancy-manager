package com.hh.gui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.model.User;
import com.hh.gui.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * Session-based auth gate for /api/** (registered in WebMvcConfig, excluding
 * /api/auth/**). Resolves the logged-in User onto the request as "currentUser"
 * for controllers to read via @RequestAttribute — deliberately not the full
 * spring-boot-starter-security starter, see WebMvcConfig for why.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserRepository userRepo;

    public AuthInterceptor(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        Long userId = session != null ? (Long) session.getAttribute("userId") : null;

        if (userId == null) {
            return unauthorized(response);
        }

        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            session.invalidate();
            return unauthorized(response);
        }

        request.setAttribute("currentUser", userOpt.get());
        return true;
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", "Требуется вход в систему")));
        return false;
    }
}
