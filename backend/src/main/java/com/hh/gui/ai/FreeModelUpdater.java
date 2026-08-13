package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the OpenRouter provider's free-model fallback list alive and actually usable.
 *
 * The ":free" pool is volatile — models get promoted to paid, renamed, removed, or
 * silently blocked upstream — and a dead id in the "models" array degrades every AI
 * call. This used to be decided purely by catalog membership: if all configured ids
 * were still listed as free, the refresh reported "unchanged" and stopped.
 *
 * That test was too weak, and it failed in production. Verified on 2026-08-13:
 * google/gemma-4-31b-it:free WAS listed in the free catalog and returned HTTP 403
 * "Blocked by Google AI Studio" on every single call. Catalog membership and being
 * answerable are different properties, so one of the three slots OpenRouter allows
 * sat permanently dead while this class reported everything fine.
 *
 * So the question asked here is no longer "is it still listed?" but "does it answer
 * the kind of request this app actually makes?" — each candidate gets a tiny real
 * request asking for a small JSON array, and only models that return a parseable one
 * are eligible. That directly catches blocked models, models that can't hold JSON
 * discipline, and moderation models that reply with a verdict instead of the task
 * (the id-based isJunkModel heuristic stays as a free pre-filter, not the only guard).
 *
 * It also removes an LLM call rather than adding one: ranking used to be delegated to
 * a model judging its peers from names and descriptions, which is both a paid
 * round-trip and a guess about behaviour nobody measured. Probing measures it.
 *
 * Runs on PipelineScheduler's 12-hour trigger and on demand via
 * POST /api/settings/providers/refresh-free-models.
 */
@Component
public class FreeModelUpdater {

    private static final Logger log = LoggerFactory.getLogger(FreeModelUpdater.class);

    private static final String MODELS_API_URL = "https://openrouter.ai/api/v1/models";
    // OpenRouter rejects "models" fallback arrays longer than 3 (see VacancyAiAnalyzer.callLlm).
    static final int MODELS_IN_CHAIN = 3;
    // Upper bound on probes per refresh, so a bad day in the free pool can't turn one
    // scheduled refresh into dozens of calls. Reached only when many candidates in a row
    // fail; the common case is 3 probes (the current chain, all healthy).
    static final int MAX_PROBES_PER_REFRESH = 12;
    // Deliberately shaped like the real workload — a JSON array and nothing else — so a
    // model that cannot hold format discipline fails here rather than mid-batch.
    private static final String PROBE_PROMPT =
        "Верни строго JSON-массив: [{\"id\":\"1\",\"ok\":true}]. Никакого текста вне массива.";
    // Roomy on purpose: a reasoning model that spends its budget thinking would otherwise
    // look dead at a tight cap, and we want to reject models for what they answer, not for
    // being cut off. Still tiny next to a real analysis batch.
    private static final int PROBE_MAX_TOKENS = 800;

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

        summary.put("current", current);
        summary.put("droppedFromFreePool", current.stream().filter(m -> !freeIds.contains(m)).toList());

        Probe probe = new Probe();

        // The current chain is probed FIRST, every refresh — that is the whole point. A
        // model can sit in the free catalog and still refuse every request, which is
        // exactly how a dead slot survived here unnoticed. Being listed is not evidence.
        List<String> healthy = new ArrayList<>();
        List<String> unhealthy = new ArrayList<>();
        for (String id : current) {
            if (probe.isHealthy(id)) healthy.add(id); else unhealthy.add(id);
        }
        summary.put("healthyCurrent", List.copyOf(healthy));
        summary.put("unhealthyCurrent", List.copyOf(unhealthy));

        if (healthy.size() == MODELS_IN_CHAIN) {
            summary.put("status", "unchanged");
            summary.put("probes", probe.used);
            log.info("Обновление free-моделей: все {} текущих моделей отвечают — список не тронут", healthy.size());
            return summary;
        }
        if (!unhealthy.isEmpty()) {
            log.warn("Обновление free-моделей: не отвечают {} — ищем замену", unhealthy);
        }

        // Fill the remaining slots from the catalog, best-looking candidates first, but
        // only keeping the ones that actually answer. Ranking is by capability now; the
        // former LLM ranking call was a paid guess about behaviour this measures directly.
        for (String id : rankedCandidateIds(candidates)) {
            if (healthy.size() >= MODELS_IN_CHAIN) break;
            if (healthy.contains(id) || unhealthy.contains(id)) continue;
            if (probe.exhausted()) {
                log.warn("Обновление free-моделей: исчерпан лимит проверок ({})", MAX_PROBES_PER_REFRESH);
                break;
            }
            if (probe.isHealthy(id)) healthy.add(id);
        }
        summary.put("probes", probe.used);

        if (healthy.isEmpty()) {
            // Never leave the provider with an empty model string — a chain that fails is
            // still better than one that cannot even be addressed, and the next refresh
            // re-checks. This is also the shape of a transient outage across the pool.
            log.error("Обновление free-моделей: ни одна модель не ответила — список не тронут");
            summary.put("status", "error");
            summary.put("reason", "ни одна из проверенных моделей не ответила");
            return summary;
        }

        if (healthy.equals(current)) {
            summary.put("status", "unchanged");
            return summary;
        }

        target.setModel(String.join(", ", healthy));
        runtimeConfig.setAiProviders(providers);
        log.warn("Обновление free-моделей: список заменён {} -> {}", current, healthy);
        summary.put("status", "updated");
        summary.put("selected", List.copyOf(healthy));
        return summary;
    }

    /** Counts probes so one refresh can't run away, and keeps the pacing in one place. */
    private class Probe {
        int used = 0;

        boolean exhausted() {
            return used >= MAX_PROBES_PER_REFRESH;
        }

        boolean isHealthy(String modelId) {
            if (used > 0) pause();   // no pause before the first probe
            used++;
            return probeModel(modelId);
        }

        /**
         * Same spacing the analyzer uses between real calls. Without it a burst of probes
         * would collect 429s and condemn perfectly good models for being asked too fast.
         */
        private void pause() {
            try {
                Thread.sleep(Math.max(0, runtimeConfig.getAiRequestDelayMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * One tiny real request. Healthy means "came back with a parseable JSON array" —
     * the same shape every real batch needs.
     *
     * A rate-limited answer is NOT unhealthy: 429 says the pool is busy, nothing about
     * this model's fitness, and demoting a good model over it would make the chain worse
     * exactly when it is under pressure. Protected for tests.
     */
    protected boolean probeModel(String modelId) {
        try {
            String response = analyzer.callLlm(PROBE_PROMPT, PROBE_MAX_TOKENS, modelId);
            Map<?, ?> parsed = mapper.readValue(response, Map.class);
            List<?> choices = (List<?>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) return false;
            Object message = ((Map<?, ?>) choices.get(0)).get("message");
            String content = message instanceof Map<?, ?> m ? (String) m.get("content") : null;
            if (content == null || content.isBlank()) return false;
            String jsonArray = VacancyAiAnalyzer.extractJsonArray(content);
            if (jsonArray == null) return false;
            return !((List<?>) mapper.readValue(jsonArray, List.class)).isEmpty();
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.RATE_LIMIT) {
                log.info("Проверка модели {}: 429 — считаем исправной, судить по нагрузке пула нельзя", modelId);
                return true;
            }
            log.info("Проверка модели {}: не прошла ({}: {})", modelId, e.kind(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.info("Проверка модели {}: не прошла ({})", modelId, e.getMessage());
            return false;
        }
    }

    /** Catalog order to try replacements in: instruct-looking first, then widest context. */
    static List<String> rankedCandidateIds(List<FreeModel> candidates) {
        return candidates.stream()
            .sorted(Comparator
                .comparing((FreeModel m) -> !looksInstruct(m.id()))
                .thenComparing(Comparator.comparingLong(FreeModel::contextLength).reversed()))
            .map(FreeModel::id)
            .toList();
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

    private static boolean looksInstruct(String id) {
        String lower = id.toLowerCase();
        return lower.contains("instruct") || lower.contains("-it:") || lower.contains("chat");
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
        String body = HttpUtil.readBody(conn, code);
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
