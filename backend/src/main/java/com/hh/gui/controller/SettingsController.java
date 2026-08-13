package com.hh.gui.controller;

import com.hh.gui.ai.FreeModelUpdater;
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
    private final FreeModelUpdater freeModelUpdater;

    public SettingsController(RuntimeConfig runtimeConfig, FreeModelUpdater freeModelUpdater) {
        this.runtimeConfig = runtimeConfig;
        this.freeModelUpdater = freeModelUpdater;
    }

    /** Manual trigger of the free-model refresh (see FreeModelUpdater) — same pass the 12-hour schedule runs. */
    @PostMapping("/providers/refresh-free-models")
    public ResponseEntity<?> refreshFreeModels(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        return ResponseEntity.ok(freeModelUpdater.refresh());
    }

    @GetMapping
    public ResponseEntity<?> getSettings(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", maskedValues());
        result.put("descriptors", runtimeConfig.getDescriptors());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body,
                                                                @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));

        Map<String, String> errors = runtimeConfig.apply(body);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", maskedValues());
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * runtimeConfig.toMap() carries full aiProviders API keys — it's also what gets
     * written verbatim to runtime-config.json, where the real keys must stay. Any
     * response leaving this process over HTTP needs them masked instead, same as
     * GET /providers already does.
     */
    private Map<String, Object> maskedValues() {
        Map<String, Object> values = new LinkedHashMap<>(runtimeConfig.toMap());
        values.put("aiProviders", runtimeConfig.getAiProviders().stream()
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("url", p.getUrl());
                m.put("apiKey", maskKey(p.getApiKey()));
                m.put("model", p.getModel());
                m.put("requestDelayMs", p.getRequestDelayMs());
                return m;
            })
            .collect(Collectors.toList()));
        return values;
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() > 8) return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        return "****";
    }

    @GetMapping("/providers")
    public ResponseEntity<?> getProviders(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();

        List<Map<String, Object>> result = runtimeConfig.getAiProviders().stream()
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("url", p.getUrl());
                m.put("apiKey", maskKey(p.getApiKey()));
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

        // GET /providers now sends only masked keys, so the edit form round-trips a
        // masked value for any provider the admin didn't retype a new key for. Compare
        // positionally against the previously stored list and keep the real key in that
        // case — otherwise every save would overwrite it with the mask string itself.
        List<AiProviderConfig> oldProviders = runtimeConfig.getAiProviders();
        List<AiProviderConfig> providers = new ArrayList<>();
        for (Map<String, Object> m : providersList) {
            AiProviderConfig p = new AiProviderConfig();
            Object name = m.get("name");
            p.setName(name != null ? name.toString() : "Provider " + (providers.size() + 1));
            Object url = m.get("url");
            p.setUrl(url != null ? url.toString() : "");
            Object key = m.get("apiKey");
            String incomingKey = key != null ? key.toString() : "";
            AiProviderConfig old = providers.size() < oldProviders.size() ? oldProviders.get(providers.size()) : null;
            if (old != null && incomingKey.equals(maskKey(old.getApiKey()))) {
                p.setApiKey(old.getApiKey());
            } else {
                p.setApiKey(incomingKey);
            }
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
