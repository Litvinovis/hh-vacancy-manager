package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FreeModelUpdaterTest {

    private static final String CURRENT_LIST = "a/one:free, b/two:free, c/three:free";

    private RuntimeConfig config;

    /** Каталог и результат проверки живости задаются полями; HTTP и реальный LLM не трогаются. */
    private static class TestUpdater extends FreeModelUpdater {
        List<FreeModel> catalog;
        /** Модели, справляющиеся с задачей (получают максимальный балл). */
        Set<String> healthy = Set.of();
        /** Точные баллы, если нужно сравнить кандидатов между собой. */
        Map<String, Integer> scoreById = new java.util.HashMap<>();
        /** Модели, чья проверка падает с 429. */
        Set<String> rateLimited = Set.of();
        final List<String> probed = new ArrayList<>();

        TestUpdater(RuntimeConfig config) {
            super(config, null);
        }

        @Override
        protected List<FreeModel> fetchFreeModels() {
            return catalog;
        }

        @Override
        protected int probeModel(String modelId) {
            probed.add(modelId);
            if (rateLimited.contains(modelId)) return FreeModelUpdater.RATE_LIMITED;
            if (scoreById.containsKey(modelId)) return scoreById.get(modelId);
            return healthy.contains(modelId) ? FreeModelUpdater.PROBE_MAX_SCORE : 0;
        }
    }

    private static FreeModelUpdater.FreeModel model(String id, long context) {
        return new FreeModelUpdater.FreeModel(id, id, "описание " + id, context);
    }

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig();
        config.setAiRequestDelayMs(0);   // без пауз между проверками в тестах
        config.setAiProviders(List.of(
            new AiProviderConfig("openrouter", "https://openrouter.ai/api/v1/chat/completions", "key", CURRENT_LIST),
            new AiProviderConfig("github-models", "https://models.inference.ai.azure.com/x", "key2", "gpt-4o-mini")));
    }

    private String openrouterModel() {
        return config.getAiProviders().get(0).getModel();
    }

    @Test
    void allCurrentModelsAnswer_changesNothing() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000), model("d/new:free", 200_000));
        updater.healthy = Set.of("a/one:free", "b/two:free", "c/three:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("unchanged", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
        assertEquals(List.of("a/one:free", "b/two:free", "c/three:free"), updater.probed,
            "в спокойном случае — ровно три проверки текущей цепочки, ничего лишнего");
    }

    @Test
    void modelStillInCatalogButBlocked_getsReplaced() {
        // Ровно продовый случай 2026-08-13: google/gemma-4-31b-it:free числилась в
        // бесплатном каталоге и отвечала 403 «Blocked by Google AI Studio» на каждый
        // вызов. Прежняя проверка «есть в каталоге» такое пропускала навсегда.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000), model("d/new-instruct:free", 200_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "d/new-instruct:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals(List.of("b/two:free"), summary.get("unhealthyCurrent"));
        assertEquals("a/one:free, c/three:free, d/new-instruct:free", openrouterModel());
    }

    @Test
    void modelDroppedFromCatalog_isReported() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("d/new-instruct:free", 200_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "d/new-instruct:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals(List.of("b/two:free"), summary.get("droppedFromFreePool"));
        assertEquals("a/one:free, c/three:free, d/new-instruct:free", openrouterModel());
    }

    @Test
    void rateLimitedProbe_doesNotCondemnTheModel() {
        // 429 говорит о загруженности пула, а не о пригодности модели. Понижать её из-за
        // этого значило бы портить цепочку ровно тогда, когда она под нагрузкой.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000), model("d/new:free", 500_000));
        updater.healthy = Set.of("a/one:free", "c/three:free");
        updater.rateLimited = Set.of("b/two:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("unchanged", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
    }

    @Test
    void equalScores_widerContextWins() {
        // Эвристика «instruct» теперь задаёт только порядок проверки, а не исход: базовую
        // модель, плохо следующую инструкциям, отсеет сам балл. Поэтому при РАВНОЙ
        // измеренной способности выигрывает больший контекст — кириллица токенизируется
        // плохо, и запас реально полезен.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("z/plain-huge:free", 900_000), model("d/big-instruct:free", 300_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "z/plain-huge:free", "d/big-instruct:free");

        updater.refresh();

        assertEquals("a/one:free, c/three:free, z/plain-huge:free", openrouterModel());
    }

    @Test
    void lowerScoringModelLoses_evenWithHugeContext() {
        // Обратная сторона того же правила и прямой ответ на «в пуле бывают откровенно
        // плохие»: огромный контекст не спасает модель, которая хуже делает задачу.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("z/plain-huge:free", 900_000), model("d/big-instruct:free", 300_000));
        updater.healthy = Set.of("a/one:free", "c/three:free");
        updater.scoreById = new java.util.HashMap<>(Map.of(
            "a/one:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "c/three:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "z/plain-huge:free", 5,
            "d/big-instruct:free", FreeModelUpdater.PROBE_MAX_SCORE));

        updater.refresh();

        assertEquals("a/one:free, c/three:free, d/big-instruct:free", openrouterModel());
    }

    @Test
    void deadCandidatesAreSkippedUntilAHealthyOneIsFound() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("dead1/instruct:free", 400_000), model("dead2/instruct:free", 300_000),
            model("alive/instruct:free", 200_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "alive/instruct:free");

        updater.refresh();

        assertEquals("a/one:free, c/three:free, alive/instruct:free", openrouterModel());
        assertTrue(updater.probed.containsAll(List.of("dead1/instruct:free", "dead2/instruct:free")),
            "мёртвые кандидаты должны быть проверены и отброшены, а не выбраны вслепую");
    }

    @Test
    void probeBudgetIsBounded() {
        TestUpdater updater = new TestUpdater(config);
        List<FreeModelUpdater.FreeModel> many = new ArrayList<>(List.of(
            model("a/one:free", 100_000), model("b/two:free", 100_000), model("c/three:free", 100_000)));
        for (int i = 0; i < 50; i++) many.add(model("dead" + i + "/instruct:free", 100_000));
        updater.catalog = many;
        updater.healthy = Set.of();   // не отвечает вообще никто

        Map<String, Object> summary = updater.refresh();

        assertEquals("error", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel(), "если не ответил никто — список не трогаем");
        assertTrue(updater.probed.size() <= FreeModelUpdater.MAX_PROBES_PER_REFRESH,
            "один прогон не должен превращаться в десятки вызовов, было: " + updater.probed.size());
    }

    @Test
    void partialHealth_keepsShorterButWorkingChain() {
        // Лучше цепочка из двух живых, чем из трёх с мёртвой внутри.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000));
        updater.healthy = Set.of("a/one:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals("a/one:free", openrouterModel());
    }

    @Test
    void guardAndSafetyModels_neverSelected() {
        // Живой инцидент: nvidia/nemotron-3.5-content-safety:free отвечала
        // "User Safety: safe" вместо задачи. Фильтр по id — бесплатный отсев до проверки.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("nvidia/content-safety:free", 500_000), model("x/llama-guard:free", 500_000),
            model("d/new-instruct:free", 200_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "d/new-instruct:free",
            "nvidia/content-safety:free", "x/llama-guard:free");

        updater.refresh();

        assertFalse(openrouterModel().contains("safety"));
        assertFalse(openrouterModel().contains("guard"));
        assertFalse(updater.probed.contains("nvidia/content-safety:free"),
            "отсев по id должен срабатывать до платной проверки");
    }

    @Test
    void noFreeOpenrouterProvider_skipped() {
        config.setAiProviders(List.of(
            new AiProviderConfig("openrouter", "https://openrouter.ai/api/v1/chat/completions", "key", "openai/gpt-4o")));
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("d/new:free", 200_000));

        Map<String, Object> summary = updater.refresh();

        assertEquals("skipped", summary.get("status"));
        assertEquals("openai/gpt-4o", openrouterModel());
        assertTrue(updater.probed.isEmpty(), "чужого провайдера не трогаем и не проверяем");
    }

    @Test
    void catalogUnavailable_keepsCurrentList() {
        TestUpdater updater = new TestUpdater(config) {
            @Override
            protected List<FreeModel> fetchFreeModels() {
                throw new IllegalStateException("HTTP 503");
            }
        };

        Map<String, Object> summary = updater.refresh();

        assertEquals("error", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
        assertTrue(updater.probed.isEmpty(), "без каталога проверять нечего");
    }

    @Test
    void tooFewCandidates_keepsCurrentList() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000));

        Map<String, Object> summary = updater.refresh();

        assertEquals("error", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
    }

    // ── Модель осталась живой, но перестала быть бесплатной ──

    @Test
    void modelAliveButNoLongerFree_isDroppedWithoutProbing() {
        // Проверка живости такую модель пропустила бы — она прекрасно отвечает. Свойство,
        // которое здесь важно, другое: она больше не бесплатна. И проверять её нельзя,
        // потому что сам проверочный запрос уже был бы оплачен.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("d/new-instruct:free", 200_000));   // b/two больше нет в бесплатном каталоге
        updater.healthy = Set.of("a/one:free", "b/two:free", "c/three:free", "d/new-instruct:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals(List.of("b/two:free"), summary.get("droppedFromFreePool"));
        assertFalse(updater.probed.contains("b/two:free"),
            "платную модель нельзя проверять — запрос был бы оплачен");
        assertFalse(openrouterModel().contains("b/two:free"));
    }

    @Test
    void catalogPricingIsCheckedNotJustTheFreeSuffix() {
        assertTrue(FreeModelUpdater.isFreePricing(Map.of("prompt", "0", "completion", "0")));
        assertFalse(FreeModelUpdater.isFreePricing(Map.of("prompt", "0.0000005", "completion", "0")));
        assertFalse(FreeModelUpdater.isFreePricing(Map.of("prompt", "0")), "нет completion — не доказано");
        assertFalse(FreeModelUpdater.isFreePricing(null));
        assertFalse(FreeModelUpdater.isFreePricing(Map.of("prompt", "н/д", "completion", "н/д")),
            "нечитаемая цена — не повод начинать платить");
    }

    // ── Выбор лучших, а не первых попавшихся ──

    @Test
    void strongerModelWinsOverBiggerContext() {
        // Ровно то, чего боялись: слабая модель с огромным контекстом не должна обходить
        // способную. Контекст решает только при равных баллах.
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("weak/instruct:free", 900_000), model("strong/instruct:free", 120_000));
        updater.healthy = Set.of("a/one:free", "c/three:free");
        updater.scoreById = new java.util.HashMap<>(Map.of(
            "a/one:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "c/three:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "weak/instruct:free", 5,      // формат держит, но задачу понимает плохо
            "strong/instruct:free", 8));  // справляется полностью

        updater.refresh();

        assertEquals("a/one:free, c/three:free, strong/instruct:free", openrouterModel(),
            "выбирать нужно по измеренной способности, а не по размеру контекста");
    }

    @Test
    void modelBelowScoreThreshold_isNotEligible() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("bad/instruct:free", 900_000), model("ok/instruct:free", 100_000));
        updater.healthy = Set.of("a/one:free", "c/three:free");
        updater.scoreById = new java.util.HashMap<>(Map.of(
            "a/one:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "c/three:free", FreeModelUpdater.PROBE_MAX_SCORE,
            "bad/instruct:free", FreeModelUpdater.PROBE_MIN_SCORE - 1,
            "ok/instruct:free", FreeModelUpdater.PROBE_MIN_SCORE));

        updater.refresh();

        assertFalse(openrouterModel().contains("bad/instruct:free"),
            "ниже порога — модель непригодна, даже если что-то ответила");
        assertTrue(openrouterModel().contains("ok/instruct:free"));
    }

    @Test
    void currentModelFallingBelowThreshold_isReplaced() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000), model("d/new-instruct:free", 200_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "d/new-instruct:free");
        updater.scoreById = new java.util.HashMap<>(Map.of("b/two:free", 3));  // деградировала

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals(List.of("b/two:free"), summary.get("unhealthyCurrent"));
        assertFalse(openrouterModel().contains("b/two:free"));
    }

    // ── Оценка ответа проверки ──

    @Test
    void scoreProbeAnswer_perfectAnswerScoresMax() {
        List<Object> items = List.of(
            Map.of("id", "p1", "score", 70, "verdict", "yes", "reason", "чат с клиентами",
                   "noveltyColor", "red", "noveltyNote", "работа по скрипту"),
            Map.of("id", "p2", "score", 0, "verdict", "no", "reason", "требуется C++",
                   "noveltyColor", "yellow", "noveltyNote", "инженерная работа"));
        assertEquals(FreeModelUpdater.PROBE_MAX_SCORE, FreeModelUpdater.scoreProbeAnswer(items));
    }

    @Test
    void scoreProbeAnswer_missingFieldsLoseThePointsForIt() {
        // Именно этот случай мучил прод: слабые модели молча опускают трудные поля.
        List<Object> items = List.of(
            Map.of("id", "p1", "score", 70, "verdict", "yes", "reason", "чат"),
            Map.of("id", "p2", "score", 0, "verdict", "no", "reason", "C++"));
        int score = FreeModelUpdater.scoreProbeAnswer(items);
        assertTrue(score < FreeModelUpdater.PROBE_MAX_SCORE, "пропущенные поля должны стоить баллов");
        assertTrue(score >= 2, "структура в остальном верна — не ноль");
    }

    @Test
    void scoreProbeAnswer_wrongSemanticsLosesThePoints() {
        // Ответила формально безупречно, но senior C++ по брифу «без программирования»
        // назвала подходящей — задачу не поняла.
        List<Object> items = List.of(
            Map.of("id", "p1", "score", 70, "verdict", "yes", "reason", "чат",
                   "noveltyColor", "red", "noveltyNote", "скрипт"),
            Map.of("id", "p2", "score", 90, "verdict", "yes", "reason", "интересно",
                   "noveltyColor", "green", "noveltyNote", "разработка"));
        assertEquals(FreeModelUpdater.PROBE_MAX_SCORE - 2, FreeModelUpdater.scoreProbeAnswer(items));
    }

    @Test
    void scoreProbeAnswer_hallucinatedIdsScoreLow() {
        List<Object> items = List.of(
            Map.of("id", "выдуманный", "score", 50, "verdict", "yes", "reason", "x",
                   "noveltyColor", "red", "noveltyNote", "y"));
        assertTrue(FreeModelUpdater.scoreProbeAnswer(items) < FreeModelUpdater.PROBE_MIN_SCORE,
            "модель, придумавшая свои id, задачу не выполнила");
    }

    @Test
    void scoreProbeAnswer_emptyOrGarbageScoresZero() {
        assertEquals(0, FreeModelUpdater.scoreProbeAnswer(List.of()));
        assertEquals(0, FreeModelUpdater.scoreProbeAnswer(null));
        assertEquals(0, FreeModelUpdater.scoreProbeAnswer(List.of("не объект")));
    }
}
