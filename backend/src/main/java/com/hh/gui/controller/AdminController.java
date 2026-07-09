package com.hh.gui.controller;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.User;
import com.hh.gui.service.SearchService;
import com.hh.gui.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User + shared-search management for the owner — every endpoint here checks
 * isAdmin() itself (a handful of endpoints, not worth a second interceptor
 * layer; AuthInterceptor already guarantees a logged-in user got this far).
 * Creating/editing/deleting a global search itself still goes through the
 * regular /api/searches CRUD (with isGlobal:true) — SearchService already lets
 * any admin bypass ownership checks there; this controller only adds the
 * "list every global search, not just mine" view those endpoints don't offer.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final SearchService searchService;

    public AdminController(UserService userService, SearchService searchService) {
        this.userService = userService;
        this.searchService = searchService;
    }

    @GetMapping("/global-searches")
    public ResponseEntity<?> listGlobalSearches(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        return ResponseEntity.ok(searchService.listGlobal());
    }

    @GetMapping("/users")
    public ResponseEntity<?> list(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        List<Map<String, Object>> result = userService.listAll().stream().map(this::toView).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/users")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body, @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "Логин обязателен"));
        }
        try {
            var result = userService.create(username, body.get("password"), body.get("displayName"),
                body.get("role"), body.get("city"));
            Map<String, Object> response = toView(result.user());
            response.put("generatedPassword", result.plainPassword());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                     @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        String displayName = (String) body.get("displayName");
        String city = (String) body.get("city");
        String role = (String) body.get("role");
        Boolean active = body.get("active") != null ? (Boolean) body.get("active") : null;

        Optional<User> updated = userService.update(id, displayName, city, role, active);
        if (updated.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Пользователь не найден"));
        return ResponseEntity.ok(toView(updated.get()));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        Optional<String> newPassword = userService.resetPassword(id);
        if (newPassword.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Пользователь не найден"));
        return ResponseEntity.ok(Map.of("generatedPassword", newPassword.get()));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        if (currentUser.getId().equals(id)) {
            return ResponseEntity.status(400).body(Map.of("error", "Нельзя удалить собственный аккаунт"));
        }
        boolean deleted = userService.delete(id);
        if (!deleted) return ResponseEntity.status(404).body(Map.of("error", "Пользователь не найден"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
    }

    private Map<String, Object> toView(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("city", u.getCity());
        m.put("active", u.isActive());
        return m;
    }
}
