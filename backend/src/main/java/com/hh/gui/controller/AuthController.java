package com.hh.gui.controller;

import com.hh.gui.model.User;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.service.AuthService;
import com.hh.gui.service.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * /api/auth/** is excluded from AuthInterceptor (see WebMvcConfig) since these
 * endpoints are how a session gets established in the first place — /me and
 * /change-password resolve the current user directly off the HttpSession
 * rather than via the @RequestAttribute the interceptor would otherwise set.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserRepository userRepo;
    private final LoginThrottle loginThrottle;

    public AuthController(AuthService authService, UserRepository userRepo, LoginThrottle loginThrottle) {
        this.authService = authService;
        this.userRepo = userRepo;
        this.loginThrottle = loginThrottle;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");

        if (loginThrottle.isLocked(username)) {
            long minutes = Math.max(1, loginThrottle.secondsRemaining(username) / 60);
            log.warn("Вход заблокирован после серии неудачных попыток: {}", username);
            return ResponseEntity.status(429).body(Map.of(
                "error", "Слишком много неудачных попыток. Повторите через " + minutes + " мин."));
        }

        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            loginThrottle.recordFailure(username);
            return ResponseEntity.status(401).body(Map.of("error", "Неверный логин или пароль"));
        }
        loginThrottle.recordSuccess(username);

        User user = userOpt.get();
        // Session fixation: a session id that existed BEFORE authentication must never
        // carry over into the authenticated session — otherwise anyone who managed to
        // plant a known JSESSIONID in the victim's browser (shared machine, an earlier
        // XSS, a link with a fixated id) holds a valid admin session the moment the
        // victim logs in. Dropping the old session forces a fresh id.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", user.getId());
        log.info("Вход в систему: {}", user.getUsername());
        return ResponseEntity.ok(toUserView(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        Optional<User> userOpt = currentUser(request);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }
        return ResponseEntity.ok(toUserView(userOpt.get()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Optional<User> userOpt = currentUser(request);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }
        User user = userOpt.get();

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (!authService.matches(oldPassword, user.getPasswordHash())) {
            return ResponseEntity.status(400).body(Map.of("error", "Текущий пароль неверен"));
        }
        if (newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "Новый пароль должен быть не короче 6 символов"));
        }

        userRepo.updatePasswordHash(user.getId(), authService.hash(newPassword));
        log.info("Пароль изменён: {}", user.getUsername());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Optional<User> userOpt = currentUser(request);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }
        User user = userOpt.get();
        String city = body.getOrDefault("city", user.getCity());
        String experienceSummary = body.getOrDefault("experienceSummary", user.getExperienceSummary());
        userRepo.updateProfile(user.getId(), city, experienceSummary);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private Optional<User> currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Optional.empty();
        Optional<User> userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty() || !userOpt.get().isActive()) return Optional.empty();
        return userOpt;
    }

    private Map<String, Object> toUserView(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole());
        m.put("city", user.getCity());
        m.put("experienceSummary", user.getExperienceSummary());
        return m;
    }
}
