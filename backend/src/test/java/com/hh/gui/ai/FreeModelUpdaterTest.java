package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FreeModelUpdaterTest {

    private static final String CURRENT_LIST = "a/one:free, b/two:free, c/three:free";

    private RuntimeConfig config;

    /** Каталог и ответ LLM задаются полем; HTTP и реальный LLM не трогаются. */
    private static class TestUpdater extends FreeModelUpdater {
        List<FreeModel> catalog;
        List<String> llmAnswer;
        boolean llmThrows = false;
        boolean llmCalled = false;

        TestUpdater(RuntimeConfig config) {
            super(config, null);
        }
        @Override
        protected List<FreeModel> fetchFreeModels() throws Exception {
            return catalog;
        }
        @Override
        protected List<String> askLlmForRanking(List<FreeModel> candidates, List<String> stillFree) throws Exception {
            llmCalled = true;
            if (llmThrows) throw new IllegalStateException("LLM недоступен");
            return llmAnswer;
        }
    }

    private static FreeModelUpdater.FreeModel model(String id, long context) {
        return new FreeModelUpdater.FreeModel(id, id, "описание " + id, context);
    }

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig();
        config.setAiProviders(List.of(
            new AiProviderConfig("openrouter", "https://openrouter.ai/api/v1/chat/completions", "key", CURRENT_LIST),
            new AiProviderConfig("github-models", "https://models.inference.ai.azure.com/x", "key2", "gpt-4o-mini")));
    }

    private String openrouterModel() {
        return config.getAiProviders().get(0).getModel();
    }

    @Test
    void refresh_allCurrentStillFree_changesNothingAndSkipsLlm() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("b/two:free", 100_000),
            model("c/three:free", 100_000), model("d/new:free", 200_000));

        Map<String, Object> summary = updater.refresh();

        assertEquals("unchanged", summary.get("status"));
        assertFalse(updater.llmCalled, "AI-запрос не нужен, пока весь список жив");
        assertEquals(CURRENT_LIST, openrouterModel());
    }

    @Test
    void refresh_modelLeftFreePool_replacedByLlmChoice() {
        TestUpdater updater = new TestUpdater(config);
        // b/two ушла из free-пула; каталог предлагает d/new и e/extra.
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("d/new-instruct:free", 200_000), model("e/extra:free", 50_000));
        updater.llmAnswer = List.of("a/one:free", "c/three:free", "d/new-instruct:free");

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals(List.of("b/two:free"), summary.get("droppedFromFreePool"));
        assertEquals("a/one:free, c/three:free, d/new-instruct:free", openrouterModel());
    }

    @Test
    void refresh_llmReturnsGarbage_deterministicFallbackKeepsSurvivorsFirst() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("d/big-instruct:free", 300_000), model("e/small:free", 10_000));
        updater.llmAnswer = List.of("nonexistent/model:free", "мусор");

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        // Выжившие текущие — первыми (без churn), добор — instruct с большим контекстом.
        assertEquals("a/one:free, c/three:free, d/big-instruct:free", openrouterModel());
    }

    @Test
    void refresh_llmFailure_stillProducesValidList() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("d/new-instruct:free", 200_000));
        updater.llmThrows = true;

        Map<String, Object> summary = updater.refresh();

        assertEquals("updated", summary.get("status"));
        assertEquals("a/one:free, c/three:free, d/new-instruct:free", openrouterModel());
    }

    @Test
    void refresh_guardAndSafetyModels_neverSelected() {
        TestUpdater updater = new TestUpdater(config);
        // Живой инцидент: nvidia/nemotron-3.5-content-safety:free отвечала
        // "User Safety: safe" вместо задачи — такие в кандидаты не попадают.
        updater.catalog = List.of(model("a/one:free", 100_000), model("c/three:free", 100_000),
            model("nvidia/content-safety:free", 500_000), model("x/llama-guard:free", 500_000),
            model("d/new-instruct:free", 200_000));
        updater.llmThrows = true; // детерминированный путь, где контекст решает

        updater.refresh();

        assertFalse(openrouterModel().contains("safety"));
        assertFalse(openrouterModel().contains("guard"));
    }

    @Test
    void refresh_noFreeOpenrouterProvider_skipped() {
        config.setAiProviders(List.of(
            new AiProviderConfig("openrouter", "https://openrouter.ai/api/v1/chat/completions", "key", "openai/gpt-4o")));
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("d/new:free", 200_000));

        Map<String, Object> summary = updater.refresh();

        assertEquals("skipped", summary.get("status"));
        assertEquals("openai/gpt-4o", openrouterModel());
    }

    @Test
    void refresh_catalogUnavailable_keepsCurrentList() {
        TestUpdater updater = new TestUpdater(config) {
            @Override
            protected List<FreeModel> fetchFreeModels() throws Exception {
                throw new IllegalStateException("HTTP 503");
            }
        };

        Map<String, Object> summary = updater.refresh();

        assertEquals("error", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
    }

    @Test
    void refresh_tooFewCandidates_keepsCurrentList() {
        TestUpdater updater = new TestUpdater(config);
        updater.catalog = List.of(model("a/one:free", 100_000));

        Map<String, Object> summary = updater.refresh();

        assertEquals("error", summary.get("status"));
        assertEquals(CURRENT_LIST, openrouterModel());
    }
}
