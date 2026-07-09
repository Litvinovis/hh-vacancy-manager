package com.hh.gui.controller;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for runtime settings — admin-only (AuthInterceptor already
 * guarantees a logged-in user; this just adds the role check per endpoint,
 * replacing the old shared hardcoded password).
 * GET /api/settings — current values + descriptors
 * POST /api/settings — update values
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final RuntimeConfig runtimeConfig;

    public SettingsController(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    @GetMapping
    public ResponseEntity<?> getSettings(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", runtimeConfig.toMap());
        result.put("descriptors", runtimeConfig.getDescriptors());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body,
                                                                @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));

        Map<String, String> errors = runtimeConfig.apply(body);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", runtimeConfig.toMap());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/providers")
    public ResponseEntity<?> getProviders(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();

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
                m.put("requestDelayMs", p.getRequestDelayMs());
                return m;
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("providers", result);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/providers")
    public ResponseEntity<?> updateProviders(@RequestBody List<Map<String, Object>> providersList,
                                              @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();

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
            Object delay = m.get("requestDelayMs");
            if (delay instanceof Number n) {
                p.setRequestDelayMs(n.intValue());
            } else if (delay instanceof String s && !s.isBlank()) {
                try {
                    p.setRequestDelayMs(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                    // пустое/нечисловое значение из формы → без переопределения
                }
            }
            providers.add(p);
        }

        runtimeConfig.setAiProviders(providers);
        log.info("Список AI провайдеров обновлён через API: {} провайдеров", providers.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("count", providers.size());
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
    }
}
