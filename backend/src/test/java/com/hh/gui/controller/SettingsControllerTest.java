package com.hh.gui.controller;

import com.hh.gui.ai.FreeModelUpdater;
import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PUT /providers is the risky spot here: GET /providers only ever hands out masked
 * keys, so the edit form round-trips a masked value for any provider the admin didn't
 * retype — updateProviders() has to tell "unchanged, keep the real key" apart from
 * "actually new key" purely by comparing against the OLD list positionally (see the
 * comment in SettingsController). That's exactly the kind of off-by-one/data-loss spot
 * worth pinning down with tests, including the case where it's known to misbehave.
 */
class SettingsControllerTest {

    private static User admin() {
        User u = new User();
        u.setId(1L);
        u.setRole("admin");
        return u;
    }

    private static User regular() {
        User u = new User();
        u.setId(2L);
        u.setRole("user");
        return u;
    }

    static class RecordingFreeModelUpdater extends FreeModelUpdater {
        final AtomicInteger calls = new AtomicInteger(0);
        RecordingFreeModelUpdater() { super(null, null); }
        @Override
        public synchronized Map<String, Object> refresh() {
            calls.incrementAndGet();
            return Map.of("status", "unchanged");
        }
    }

    private RuntimeConfig config;
    private RecordingFreeModelUpdater updater;
    private SettingsController controller;

    private void init() {
        config = new RuntimeConfig();
        updater = new RecordingFreeModelUpdater();
        controller = new SettingsController(config, updater);
    }

    private static Map<String, Object> providerMap(String name, String url, String apiKey, String model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", url);
        m.put("apiKey", apiKey);
        m.put("model", model);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> providersFromResponse() {
        var response = controller.getProviders(admin()).getBody();
        return (List<Map<String, Object>>) ((Map<String, Object>) response).get("providers");
    }

    private static Map<String, Object> providerMapWithDelay(String name, String url, String apiKey, String model, Object requestDelayMs) {
        Map<String, Object> m = providerMap(name, url, apiKey, model);
        m.put("requestDelayMs", requestDelayMs);
        return m;
    }

    // ── GET /api/settings ──

    @Test
    void getSettings_nonAdmin_returns403() {
        init();
        var response = controller.getSettings(regular());
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void getSettings_admin_returnsMaskedApiKeyInValues() {
        init();
        config.setAiProviders(List.of(new AiProviderConfig("OpenRouter", "https://openrouter.ai", "sk-1234567890abcd", "x/y")));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.getSettings(admin()).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) body.get("values");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) values.get("aiProviders");

        assertEquals("sk-1...abcd", providers.get(0).get("apiKey"), "полный ключ никогда не должен уходить по HTTP");
    }

    // ── POST /api/settings ──

    @Test
    void updateSettings_nonAdmin_returns403AndDoesNotApply() {
        init();
        var response = controller.updateSettings(Map.of("moderationMode", "single"), regular());
        assertEquals(403, response.getStatusCode().value());
        assertEquals("auto", config.getModerationMode(), "неавторизованное обновление не должно применяться");
    }

    @Test
    void updateSettings_admin_invalidValue_returnsErrorsWithoutCrashing() {
        init();
        var response = controller.updateSettings(Map.of("moderationMode", "not-a-real-mode"), admin());
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) response.getBody().get("errors");
        assertNotNull(errors);
        assertTrue(errors.containsKey("moderationMode"));
        assertEquals("auto", config.getModerationMode(), "невалидное значение не должно записываться");
    }

    @Test
    void updateSettings_admin_validValue_appliesWithNoErrors() {
        init();
        var response = controller.updateSettings(Map.of("moderationMode", "single"), admin());
        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getBody().get("errors"));
        assertEquals("single", config.getModerationMode());
    }

    // ── GET /api/settings/providers ──

    @Test
    void getProviders_nonAdmin_returns403() {
        init();
        var response = controller.getProviders(regular());
        assertEquals(403, response.getStatusCode().value());
    }

    // ── PUT /api/settings/providers ──

    @Test
    void updateProviders_nonAdmin_returns403WithoutChangingStoredProviders() {
        init();
        config.setAiProviders(List.of(new AiProviderConfig("A", "u", "realkey123456", "m")));
        var response = controller.updateProviders(List.of(providerMap("A", "u", "changed", "m")), regular());
        assertEquals(403, response.getStatusCode().value());
        assertEquals("realkey123456", config.getAiProviders().get(0).getApiKey());
    }

    @Test
    void updateProviders_emptyList_clearsStoredProviders() {
        init();
        config.setAiProviders(List.of(new AiProviderConfig("A", "u", "realkey123456", "m")));
        controller.updateProviders(new ArrayList<>(), admin());
        assertTrue(config.getAiProviders().isEmpty());
    }

    @Test
    void updateProviders_newProviderWithRealKey_keepsGivenKeyAsIs() {
        init();
        controller.updateProviders(List.of(providerMap("New", "u", "sk-brandnewkey123", "m")), admin());
        assertEquals("sk-brandnewkey123", config.getAiProviders().get(0).getApiKey());
    }

    @Test
    void updateProviders_maskedValueAtSamePosition_restoresRealOldKey() {
        init();
        config.setAiProviders(List.of(new AiProviderConfig("A", "u", "sk-1234567890abcd", "m")));
        // GET /providers would have handed the UI exactly this masked string back.
        String masked = "sk-1...abcd";

        controller.updateProviders(List.of(providerMap("A", "u2", masked, "m2")), admin());

        assertEquals("sk-1234567890abcd", config.getAiProviders().get(0).getApiKey(),
            "немодифицированный (замаскированный) ключ должен сохранить реальное значение");
        assertEquals("u2", config.getAiProviders().get(0).getUrl(), "остальные поля при этом обновляются как обычно");
    }

    @Test
    void updateProviders_reorderedListWithMaskedValue_matchesByNameNotPosition() {
        // The old-vs-new comparison matches by provider name, not list index — reordering
        // providers in the UI while leaving an untouched (masked) key in its form field
        // must still resolve to that provider's real old key.
        init();
        config.setAiProviders(List.of(
            new AiProviderConfig("First", "u1", "sk-1234567890abcd", "m1"),
            new AiProviderConfig("Second", "u2", "sk-99999999zzzz", "m2")
        ));
        String firstsMaskedKey = "sk-1...abcd"; // what GET /providers showed for "First" at index 0

        // Admin swaps the order in the UI; "First" (with its still-masked key) is now at index 1.
        controller.updateProviders(List.of(
            providerMap("Second", "u2", "sk-9...zzzz", "m2"),
            providerMap("First", "u1", firstsMaskedKey, "m1")
        ), admin());

        List<AiProviderConfig> saved = config.getAiProviders();
        assertEquals("First", saved.get(1).getName());
        assertEquals("sk-1234567890abcd", saved.get(1).getApiKey(),
            "сравнение по имени должно найти реальный ключ 'First' даже после перестановки");
    }

    @Test
    void updateProviders_duplicateOldNames_matchesFirstOccurrence() {
        // Edge case of name-based matching: if the old list somehow has two providers
        // sharing a name, the lookup deterministically keeps the first one rather than
        // crashing or picking arbitrarily on each call.
        init();
        config.setAiProviders(List.of(
            new AiProviderConfig("Dup", "u1", "sk-first-0000", "m1"),
            new AiProviderConfig("Dup", "u2", "sk-second-0000", "m2")
        ));
        String maskedFirst = "sk-f...0000";

        controller.updateProviders(List.of(providerMap("Dup", "u1", maskedFirst, "m1")), admin());

        assertEquals("sk-first-0000", config.getAiProviders().get(0).getApiKey());
    }

    @Test
    void updateProviders_requestDelayMsAsNumericString_isParsed() {
        init();
        controller.updateProviders(List.of(providerMapWithDelay("A", "u", "k", "m", "1500")), admin());
        assertEquals(1500, config.getAiProviders().get(0).getRequestDelayMs());
    }

    @Test
    void updateProviders_requestDelayMsBlankString_ignoredWithoutError() {
        init();
        controller.updateProviders(List.of(providerMapWithDelay("A", "u", "k", "m", "  ")), admin());
        assertNull(config.getAiProviders().get(0).getRequestDelayMs());
    }

    @Test
    void updateProviders_requestDelayMsNonNumericString_ignoredWithoutThrowing() {
        init();
        assertDoesNotThrow(() ->
            controller.updateProviders(List.of(providerMapWithDelay("A", "u", "k", "m", "not-a-number")), admin()));
        assertNull(config.getAiProviders().get(0).getRequestDelayMs());
    }

    @Test
    void updateProviders_missingName_defaultsToProviderPlusOneBasedIndex() {
        init();
        Map<String, Object> noName = providerMap(null, "u", "k", "m");
        noName.remove("name");
        controller.updateProviders(List.of(noName), admin());
        assertEquals("Provider 1", config.getAiProviders().get(0).getName());
    }

    // ── POST /api/settings/providers/refresh-free-models ──

    @Test
    void refreshFreeModels_nonAdmin_returns403WithoutCallingUpdater() {
        init();
        var response = controller.refreshFreeModels(regular());
        assertEquals(403, response.getStatusCode().value());
        assertEquals(0, updater.calls.get());
    }

    @Test
    void refreshFreeModels_admin_delegatesToUpdater() {
        init();
        var response = controller.refreshFreeModels(admin());
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, updater.calls.get());
    }
}
