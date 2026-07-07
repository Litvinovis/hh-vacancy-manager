package com.hh.gui.controller;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * Получить список AI провайдеров (с маскированными ключами).
     * GET /api/settings/providers?password=...
     */
    @GetMapping("/providers")
    public ResponseEntity<?> getProviders(@RequestParam String password) {
        if (!SETTINGS_PASSWORD.equals(password)) {
            return ResponseEntity.status(403).body(Map.of("error", "Неверный пароль"));
        }

        List<Map<String, Object>> result = runtimeConfig.getAiProviders().stream()
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("url", p.getUrl());
                // Маскируем ключ для отображения
                String key = p.getApiKey();
                if (key != null && key.length() > 8) {
                    m.put("apiKey", key.substring(0, 4) + "..." + key.substring(key.length() - 4));
                } else if (key != null && !key.isBlank()) {
                    m.put("apiKey", "****");
                } else {
                    m.put("apiKey", "");
                }
                m.put("apiKeyFull", p.getApiKey()); // полный ключ для редактирования (уйдёт в форму)
                m.put("model", p.getModel());
                return m;
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("providers", result);
        return ResponseEntity.ok(response);
    }

    /**
     * Обновить список AI провайдеров.
     * PUT /api/settings/providers?password=...
     * body: [{"name": "...", "url": "...", "apiKey": "...", "model": "..."}, ...]
     */
    @PutMapping("/providers")
    public ResponseEntity<?> updateProviders(@RequestParam String password,
                                              @RequestBody List<Map<String, Object>> providersList) {
        if (!SETTINGS_PASSWORD.equals(password)) {
            return ResponseEntity.status(403).body(Map.of("error", "Неверный пароль"));
        }

        List<AiProviderConfig> providers = new ArrayList<>();
        for (Map<String, Object> m : providersList) {
            AiProviderConfig p = new AiProviderConfig();
            Object name = m.get("name");
            p.setName(name != null ? name.toString() : "Provider " + (providers.size() + 1));
            Object url = m.get("url");
            p.setUrl(url != null ? url.toString() : "");
            Object key = m.get("apiKey");
            p.setApiKey(key != null ? key.toString() : "");
            Object model = m.get("model");
            p.setModel(model != null ? model.toString() : "");
            providers.add(p);
        }

        runtimeConfig.setAiProviders(providers);
        log.info("Список AI провайдеров обновлён через API: {} провайдеров", providers.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("count", providers.size());
        return ResponseEntity.ok(result);
    }
}
