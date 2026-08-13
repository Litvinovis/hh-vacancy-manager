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
        /** Модели, которые «отвечают». Всё остальное считается мёртвым. */
        Set<String> healthy = Set.of();
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
        protected boolean probeModel(String modelId) {
            probed.add(modelId);
            if (rateLimited.contains(modelId)) {
                // Повторяем реальную ветку: 429 не является приговором модели.
                return true;
            }
            return healthy.contains(modelId);
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
    void replacementsPreferInstructThenWiderContext() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("z/plain-huge:free", 900_000), model("d/big-instruct:free", 300_000));
        updater.healthy = Set.of("a/one:free", "c/three:free", "z/plain-huge:free", "d/big-instruct:free");

        updater.refresh();

        assertEquals("a/one:free, c/three:free, d/big-instruct:free", openrouterModel(),
            "instruct-модель предпочтительнее просто большого контекста");
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
}
