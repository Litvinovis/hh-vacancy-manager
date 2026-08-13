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
    // How many candidates beyond the open slots to probe, so the score has something to
    // choose between instead of rubber-stamping whoever the heuristic listed first.
    static final int EXTRA_CANDIDATES_TO_COMPARE = 2;
    /**
     * A miniature of the real job, not a liveness ping. Asking a model to echo
     * {"ok":true} proves almost nothing — nearly anything in the free pool can do that,
     * so a 2.6B model would score the same as a capable one and could win a slot on
     * context length alone. This asks for the actual schema over two cards with an
     * obvious right answer, which measures the thing the chain is chosen for.
     *
     * Note what the second card tests: the whole novelty_color/novelty_note field pair
     * was unreliable in production precisely because weak models silently omit fields
     * they find hard. Scoring on "returned all six fields" selects against exactly that.
     */
    private static final String PROBE_PROMPT = """
        Ты — аналитик вакансий. Оцени каждую вакансию и верни строго JSON-массив.
        У КАЖДОГО элемента ровно шесть полей: id, score (0-100), verdict ("yes"/"no"/"fraud"),
        reason (до 10 слов), noveltyColor ("red"/"yellow"/"green"), noveltyNote (до 8 слов).
        Ищем: работа с людьми и текстами, без программирования.
        Никакого текста вне массива.

        ВАКАНСИИ:
        ---
        ID: p1
        Название: Оператор чата поддержки
        Описание: отвечать на вопросы клиентов в чате по скриптам
        ---
        ID: p2
        Название: Ведущий инженер-программист C++
        Описание: разработка высоконагруженного бэкенда, 10 лет опыта на C++
        """;

    /** Ids used in PROBE_PROMPT — a model that invents its own has not understood the task. */
    private static final List<String> PROBE_IDS = List.of("p1", "p2");
    /** p2 is a senior C++ role against a "no programming" brief — the unambiguous reject. */
    private static final String PROBE_REJECT_ID = "p2";
    private static final Set<String> PROBE_VALID_VERDICTS = Set.of("yes", "no", "fraud");
    private static final Set<String> PROBE_VALID_COLORS = Set.of("red", "yellow", "green");
    private static final List<String> PROBE_REQUIRED_FIELDS =
        List.of("id", "score", "verdict", "reason", "noveltyColor", "noveltyNote");

    /**
     * Capability score out of {@value #PROBE_MAX_SCORE}. Structure is worth more in total
     * than the single semantic check, because a model that cannot hold the contract breaks
     * every batch, whereas one bad judgement is what the score threshold in the pipeline is
     * already for.
     */
    static final int PROBE_MAX_SCORE = 8;
    /**
     * Below this a model is not eligible at all. Set so that returning both requested ids
     * with every field filled and valid verdicts (2+2+1 = 5) is the floor — anything less
     * means the contract itself is unreliable.
     */
    static final int PROBE_MIN_SCORE = 5;
    // Roomy on purpose: we reject models for what they answer, not for being cut off. Two
    // items with six fields each fit comfortably; the headroom is margin, and it is still
    // tiny next to a real analysis batch. (Reasoning is disabled for these calls the same
    // way it is in production — see VacancyAiAnalyzer.callLlm.)
    private static final int PROBE_MAX_TOKENS = 1200;

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

        // Two independent things can disqualify a model, and both must be checked.
        // Being listed as free protects the wallet; answering protects the pipeline.
        // Checking only one of them is how each of the two bugs here happened: the old
        // code trusted catalog membership alone and kept a 403-blocked model forever,
        // and checking health alone would keep a model that quietly went paid.
        List<String> stillFree = current.stream().filter(freeIds::contains).toList();
        List<String> leftPool = current.stream().filter(m -> !freeIds.contains(m)).toList();
        if (!leftPool.isEmpty()) {
            log.warn("Обновление free-моделей: {} больше не бесплатны — исключаем без проверки", leftPool);
        }

        Probe probe = new Probe();

        // Only models still in the free pool get probed. A probe is a real billed request,
        // so probing one that just went paid would mean paying to find out we must drop it.
        // The current chain is probed on EVERY refresh — that is the whole point: a model
        // can sit in the free catalog and still refuse every request, which is exactly how
        // a dead slot survived here unnoticed. Being listed is not evidence of working.
        List<String> healthy = new ArrayList<>();
        List<String> unhealthy = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String id : stillFree) {
            // Retried once before condemning, and ONLY for models already in the chain.
            // A single sample is a shaky basis for eviction: on 2026-08-13 one transient
            // empty answer scored nemotron-3-super-120b at 0/8 and dropped a model that
            // had been serving all day, and the replacement search then seated whatever
            // answered next. Incumbents have a track record; a fresh candidate that fails
            // has none, so it does not get the extra call.
            int score = probe.score(id);
            if (score != RATE_LIMITED && score < PROBE_MIN_SCORE && !probe.exhausted()) {
                log.info("Обновление free-моделей: {} набрала {}/{} — перепроверяем, прежде чем менять",
                    id, score, PROBE_MAX_SCORE);
                score = probe.score(id);
            }
            // A model already in the chain is kept on a rate-limited probe: we learned
            // nothing about it, and churning the chain on no information is worse.
            if (score == RATE_LIMITED || score >= PROBE_MIN_SCORE) {
                healthy.add(id);
                if (score != RATE_LIMITED) scores.put(id, score);
            } else {
                unhealthy.add(id);
                log.warn("Обновление free-моделей: {} набрала {}/{} — ниже порога {}",
                    id, score, PROBE_MAX_SCORE, PROBE_MIN_SCORE);
            }
        }
        summary.put("healthyCurrent", List.copyOf(healthy));
        summary.put("unhealthyCurrent", List.copyOf(unhealthy));
        summary.put("scores", new LinkedHashMap<>(scores));

        // healthy can only contain models that were in stillFree, so reaching a full chain
        // here already means every one of them is both free and answering.
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
        // Candidates are tried in a cheap heuristic order (instruct-looking, then widest
        // context) purely to decide WHO to spend a probe on — the heuristic never decides
        // who wins. Selection among those probed is by measured score, so a small model
        // with a huge context window cannot beat a capable one on paper.
        int needed = MODELS_IN_CHAIN - healthy.size();
        // Probe a couple MORE than strictly needed, otherwise the score only filters and
        // never actually chooses: stopping at the first passing candidates would seat them
        // by catalog order, which is the heuristic we just said must not decide.
        int wanted = needed + EXTRA_CANDIDATES_TO_COMPARE;
        Map<String, Integer> candidateScores = new LinkedHashMap<>();
        for (String id : rankedCandidateIds(candidates)) {
            if (candidateScores.size() >= wanted) break;
            if (healthy.contains(id) || unhealthy.contains(id)) continue;
            if (probe.exhausted()) {
                log.warn("Обновление free-моделей: исчерпан лимит проверок ({})", MAX_PROBES_PER_REFRESH);
                break;
            }
            int score = probe.score(id);
            if (score != RATE_LIMITED && score >= PROBE_MIN_SCORE) candidateScores.put(id, score);
        }
        // Best measured first; context breaks ties between equally capable models.
        Map<String, Long> contextById = candidates.stream()
            .collect(java.util.stream.Collectors.toMap(FreeModel::id, FreeModel::contextLength, (a, b) -> a));
        candidateScores.entrySet().stream()
            .sorted(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(e -> -contextById.getOrDefault(e.getKey(), 0L)))
            .limit(needed)
            .forEach(e -> {
                healthy.add(e.getKey());
                scores.put(e.getKey(), e.getValue());
            });
        summary.put("scores", new LinkedHashMap<>(scores));
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

        /** Score for this model, or RATE_LIMITED when the pool was too busy to judge. */
        int score(String modelId) {
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
     * Runs PROBE_PROMPT against one model and scores how well it did the job.
     * Returns 0 for anything unusable, so a score below PROBE_MIN_SCORE and an outright
     * failure are the same thing to the caller.
     *
     * A rate-limited answer is deliberately NOT a zero: 429 says the pool is busy and
     * nothing about this model's fitness, and demoting a good model over it would degrade
     * the chain exactly when it is under pressure. Those keep their current standing.
     * Protected for tests.
     */
    protected int probeModel(String modelId) {
        try {
            String response = analyzer.callLlm(PROBE_PROMPT, PROBE_MAX_TOKENS, modelId);
            Map<?, ?> parsed = mapper.readValue(response, Map.class);
            List<?> choices = (List<?>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) return 0;
            Object message = ((Map<?, ?>) choices.get(0)).get("message");
            String content = message instanceof Map<?, ?> m ? (String) m.get("content") : null;
            if (content == null || content.isBlank()) return 0;
            String jsonArray = VacancyAiAnalyzer.extractJsonArray(content);
            if (jsonArray == null) return 0;
            return scoreProbeAnswer(mapper.readValue(jsonArray, List.class));
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.RATE_LIMIT) {
                log.info("Проверка модели {}: 429 — судить по загруженности пула нельзя, оценку сохраняем", modelId);
                return RATE_LIMITED;
            }
            log.info("Проверка модели {}: не прошла ({}: {})", modelId, e.kind(), e.getMessage());
            return 0;
        } catch (Exception e) {
            log.info("Проверка модели {}: не прошла ({})", modelId, e.getMessage());
            return 0;
        }
    }

    /** Sentinel: the probe could not be judged because the pool was busy, not because the model is bad. */
    static final int RATE_LIMITED = -1;

    /**
     * Scores one probe answer. Structure carries more weight in total than the single
     * semantic check: a model that cannot hold the field contract breaks every batch it
     * ever touches, while one questionable verdict is what minScore in the pipeline is for.
     */
    static int scoreProbeAnswer(List<?> items) {
        if (items == null || items.isEmpty()) return 0;

        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Object raw : items) {
            if (raw instanceof Map<?, ?> item && item.get("id") != null) {
                byId.put(String.valueOf(item.get("id")), item);
            }
        }

        int score = 0;
        // +2 — returned exactly the cards it was given, no inventions, none dropped.
        if (byId.keySet().equals(new LinkedHashSet<>(PROBE_IDS))) score += 2;

        // +2 — every required field present and non-blank on every item. This is the check
        // that selects against models which quietly omit the fields they find hard.
        boolean allFieldsPresent = !byId.isEmpty() && byId.values().stream().allMatch(item ->
            PROBE_REQUIRED_FIELDS.stream().allMatch(f -> {
                Object v = item.get(f);
                return v != null && !String.valueOf(v).isBlank();
            }));
        if (allFieldsPresent) score += 2;

        // +1 — verdicts from the allowed set.
        boolean verdictsValid = !byId.isEmpty() && byId.values().stream()
            .allMatch(item -> PROBE_VALID_VERDICTS.contains(String.valueOf(item.get("verdict"))));
        if (verdictsValid) score += 1;

        // +1 — colors from the allowed set.
        boolean colorsValid = !byId.isEmpty() && byId.values().stream()
            .allMatch(item -> PROBE_VALID_COLORS.contains(String.valueOf(item.get("noveltyColor"))));
        if (colorsValid) score += 1;

        // +2 — actually understood the brief: a senior C++ role is not a "no programming" job.
        Map<?, ?> reject = byId.get(PROBE_REJECT_ID);
        if (reject != null && "no".equals(String.valueOf(reject.get("verdict")))) score += 2;

        return score;
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

    /**
     * True only when both prompt and completion cost exactly zero. Anything unparsable or
     * missing counts as NOT free — an unreadable price is not a reason to start paying.
     */
    static boolean isFreePricing(Object pricing) {
        if (!(pricing instanceof Map<?, ?> p)) return false;
        return isZero(p.get("prompt")) && isZero(p.get("completion"));
    }

    private static boolean isZero(Object value) {
        if (value == null) return false;
        try {
            return new java.math.BigDecimal(String.valueOf(value)).signum() == 0;
        } catch (NumberFormatException e) {
            return false;
        }
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
            // The ":free" suffix is a naming convention, and a chain built on a convention
            // would start spending real money the day it stops holding. The catalog states
            // the price outright, so check that instead of trusting the name.
            if (!isFreePricing(m.get("pricing"))) {
                log.warn("Каталог моделей: {} помечена как ':free', но цена не нулевая — исключаем", id);
                continue;
            }
            long context = m.get("context_length") instanceof Number n ? n.longValue() : 0;
            result.add(new FreeModel(id,
                m.get("name") != null ? String.valueOf(m.get("name")) : "",
                m.get("description") != null ? String.valueOf(m.get("description")) : "",
                context));
        }
        return result;
    }
}
