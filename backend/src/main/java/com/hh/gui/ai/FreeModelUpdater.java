package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the OpenRouter provider's free-model fallback list alive: the ":free"
 * pool is volatile (models get promoted to paid, renamed, or removed outright),
 * and a dead id in the "models" array silently degrades every AI call. Every
 * refresh re-reads the live model catalog; if any configured model is no longer
 * free, a short LLM round-trip picks the best replacements from what's actually
 * available. Runs on PipelineScheduler's 12-hour trigger and on demand via
 * POST /api/settings/providers/refresh-free-models.
 */
@Component
public class FreeModelUpdater {

    private static final Logger log = LoggerFactory.getLogger(FreeModelUpdater.class);

    private static final String MODELS_API_URL = "https://openrouter.ai/api/v1/models";
    // OpenRouter rejects "models" fallback arrays longer than 3 (see VacancyAiAnalyzer.callLlm).
    static final int MODELS_IN_CHAIN = 3;

    private final RuntimeConfig runtimeConfig;
    private final VacancyAiAnalyzer analyzer;
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    public FreeModelUpdater(RuntimeConfig runtimeConfig, VacancyAiAnalyzer analyzer) {
        this.runtimeConfig = runtimeConfig;
        this.analyzer = analyzer;
    }

    record FreeModel(String id, String name, String description, long contextLength) {}

    /**
     * One refresh pass. Returns a summary map (also served by the manual endpoint):
     * status = unchanged | updated | skipped | error, plus detail fields.
     */
    public synchronized Map<String, Object> refresh() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<AiProviderConfig> providers = runtimeConfig.getAiProviders();
        AiProviderConfig target = providers.stream()
            .filter(p -> p.getUrl() != null && p.getUrl().contains("openrouter"))
            .filter(p -> p.getModel() != null && p.getModel().contains(":free"))
            .findFirst().orElse(null);
        if (target == null) {
            // No provider on the free pool — someone pinned a paid model; never touch that.
            summary.put("status", "skipped");
            summary.put("reason", "нет OpenRouter-провайдера со ':free'-моделями");
            return summary;
        }

        List<FreeModel> free;
        try {
            free = fetchFreeModels();
        } catch (Exception e) {
            // A catalog-API hiccup must not degrade a working config — keep as is.
            log.warn("Обновление free-моделей: каталог OpenRouter недоступен ({}), список не тронут", e.getMessage());
            summary.put("status", "error");
            summary.put("reason", "каталог моделей недоступен: " + e.getMessage());
            return summary;
        }
        List<FreeModel> candidates = free.stream()
            .filter(m -> !isJunkModel(m.id()))
            .toList();
        if (candidates.size() < MODELS_IN_CHAIN) {
            log.warn("Обновление free-моделей: каталог вернул лишь {} пригодных моделей — список не тронут", candidates.size());
            summary.put("status", "error");
            summary.put("reason", "слишком мало кандидатов в каталоге: " + candidates.size());
            return summary;
        }

        List<String> current = List.of(target.getModel().split(",")).stream()
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        Set<String> freeIds = new LinkedHashSet<>(candidates.stream().map(FreeModel::id).toList());
        List<String> stillFree = current.stream().filter(freeIds::contains).toList();

        summary.put("current", current);
        summary.put("droppedFromFreePool", current.stream().filter(m -> !freeIds.contains(m)).toList());

        if (stillFree.size() == current.size() && current.size() == MODELS_IN_CHAIN) {
            summary.put("status", "unchanged");
            log.info("Обновление free-моделей: все {} текущих моделей по-прежнему бесплатны", current.size());
            return summary;
        }

        List<String> selected = selectModels(candidates, stillFree);
        target.setModel(String.join(", ", selected));
        runtimeConfig.setAiProviders(providers);
        log.warn("Обновление free-моделей: список заменён {} -> {}", current, selected);
        summary.put("status", "updated");
        summary.put("selected", selected);
        return summary;
    }

    /**
     * Guard/safety models answer every prompt with a moderation verdict instead of
     * doing the task — a live incident: openrouter/free routed to
     * nvidia/nemotron-3.5-content-safety:free, whose entire answer was "User Safety:
     * safe" (~20% of requests wasted before the pinned list existed).
     */
    static boolean isJunkModel(String id) {
        String lower = id.toLowerCase();
        return lower.contains("safety") || lower.contains("guard");
    }

    /**
     * Asks the current LLM chain to rank the candidates for this app's actual
     * workload; any failure (chain down, malformed answer, ids not in the pool)
     * falls back to a deterministic pick so the refresh always produces a valid
     * list. Kept-alive current models are always preferred to avoid churn.
     */
    private List<String> selectModels(List<FreeModel> candidates, List<String> stillFree) {
        try {
            List<String> ranked = askLlmForRanking(candidates, stillFree);
            LinkedHashSet<String> valid = new LinkedHashSet<>();
            Set<String> pool = new LinkedHashSet<>(candidates.stream().map(FreeModel::id).toList());
            for (String id : ranked) {
                if (pool.contains(id)) valid.add(id);
            }
            if (valid.size() >= MODELS_IN_CHAIN) {
                return List.copyOf(valid).subList(0, MODELS_IN_CHAIN);
            }
            log.warn("Обновление free-моделей: AI вернул лишь {} валидных id — детерминированный фолбэк", valid.size());
        } catch (Exception e) {
            log.warn("Обновление free-моделей: AI-выбор не удался ({}) — детерминированный фолбэк", e.getMessage());
        }
        return fallbackSelection(candidates, stillFree);
    }

    /** Still-free current models first (no churn), then instruct-looking ids by context size. */
    static List<String> fallbackSelection(List<FreeModel> candidates, List<String> stillFree) {
        LinkedHashSet<String> result = new LinkedHashSet<>(stillFree);
        candidates.stream()
            .sorted(Comparator
                .comparing((FreeModel m) -> !looksInstruct(m.id()))
                .thenComparing(Comparator.comparingLong(FreeModel::contextLength).reversed()))
            .map(FreeModel::id)
            .forEach(id -> {
                if (result.size() < MODELS_IN_CHAIN) result.add(id);
            });
        return List.copyOf(result).subList(0, Math.min(MODELS_IN_CHAIN, result.size()));
    }

    private static boolean looksInstruct(String id) {
        String lower = id.toLowerCase();
        return lower.contains("instruct") || lower.contains("-it:") || lower.contains("chat");
    }

    /** LLM round-trip: candidates in, ordered id list out. Protected for tests. */
    protected List<String> askLlmForRanking(List<FreeModel> candidates, List<String> stillFree) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Выбери ровно ").append(MODELS_IN_CHAIN).append(" модели из списка ниже для задачи: ")
          .append("классификация вакансий с hh.ru на русском языке, ответ строго JSON-массивом, ")
          .append("пакеты по 20-30 объектов. Критерии: сильный русский язык, дисциплина формата JSON, ")
          .append("instruct-модель БЕЗ длинных reasoning-рассуждений, достаточный контекст (от 30k). ")
          .append("Не выбирай модерационные/guard-модели и модели для кода.\n");
        if (!stillFree.isEmpty()) {
            sb.append("Эти модели уже используются и работают — оставь их, если нет явно лучшей замены: ")
              .append(String.join(", ", stillFree)).append("\n");
        }
        sb.append("Ответ: строго JSON-массив из ").append(MODELS_IN_CHAIN)
          .append(" строк-идентификаторов, лучшая первой, без текста вне массива.\n\nКАНДИДАТЫ:\n");
        for (FreeModel m : candidates) {
            sb.append("- ").append(m.id())
              .append(" | контекст ").append(m.contextLength())
              .append(" | ").append(truncate(m.name(), 60));
            if (m.description() != null && !m.description().isBlank()) {
                sb.append(" | ").append(truncate(m.description(), 160));
            }
            sb.append("\n");
        }

        String response = analyzer.callLlm(sb.toString(), 1500);
        Map<?, ?> parsed = mapper.readValue(response, Map.class);
        List<?> choices = (List<?>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("ответ без choices");
        String content = (String) ((Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message")).get("content");
        if (content == null) throw new IllegalStateException("ответ без content");
        String jsonArray = VacancyAiAnalyzer.extractJsonArray(content);
        if (jsonArray == null) throw new IllegalStateException("JSON-массив не найден в ответе");
        List<?> ids = mapper.readValue(jsonArray, List.class);
        return ids.stream().map(String::valueOf).toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /** Reads the live catalog. Protected for tests. */
    @SuppressWarnings("unchecked")
    protected List<FreeModel> fetchFreeModels() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(MODELS_API_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " от каталога моделей");
        String body;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            body = sb.toString();
        }
        Map<?, ?> parsed = mapper.readValue(body, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
        if (data == null) throw new IllegalStateException("каталог моделей без поля data");
        List<FreeModel> result = new ArrayList<>();
        for (Map<String, Object> m : data) {
            String id = String.valueOf(m.get("id"));
            if (!id.endsWith(":free")) continue;
            long context = m.get("context_length") instanceof Number n ? n.longValue() : 0;
            result.add(new FreeModel(id,
                m.get("name") != null ? String.valueOf(m.get("name")) : "",
                m.get("description") != null ? String.valueOf(m.get("description")) : "",
                context));
        }
        return result;
    }
}
