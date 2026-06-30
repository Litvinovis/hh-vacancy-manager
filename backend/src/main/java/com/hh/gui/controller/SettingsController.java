package com.hh.gui.controller;

import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API для runtime-настроек.
 * GET /api/settings — текущие значения + дескрипторы (требуется пароль)
 * POST /api/settings — обновить значения (требуется пароль)
 * POST /api/settings/auth — проверить пароль
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final String SETTINGS_PASSWORD = "1102";

    private final RuntimeConfig runtimeConfig;

    public SettingsController(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * Проверка пароля.
     * POST /api/settings/auth  body: {"password": "..."}
     */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> auth(@RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        boolean valid = SETTINGS_PASSWORD.equals(password);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        return ResponseEntity.ok(result);
    }

    /**
     * Получить все настройки с дескрипторами (требуется пароль).
     * GET /api/settings?password=...
     */
    @GetMapping
    public ResponseEntity<?> getSettings(@RequestParam String password) {
        if (!SETTINGS_PASSWORD.equals(password)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Неверный пароль");
            return ResponseEntity.status(403).body(error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", runtimeConfig.toMap());
        result.put("descriptors", runtimeConfig.getDescriptors());
        return ResponseEntity.ok(result);
    }

    /**
     * Обновить настройки (требуется пароль).
     * POST /api/settings  body: {"password": "...", "maxPerRun": 50, ...}
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        if (!SETTINGS_PASSWORD.equals(password)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Неверный пароль");
            return ResponseEntity.status(403).body(error);
        }

        // Убираем пароль из данных для обновления
        Map<String, Object> updates = new LinkedHashMap<>(body);
        updates.remove("password");

        Map<String, String> errors = runtimeConfig.apply(updates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", runtimeConfig.toMap());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }
}
