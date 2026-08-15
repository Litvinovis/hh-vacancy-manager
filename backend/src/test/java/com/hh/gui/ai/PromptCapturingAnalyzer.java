package com.hh.gui.ai;

import com.hh.gui.config.RuntimeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the LLM in integration tests: records the prompt it was asked to send
 * and answers with a canned verdict for every vacancy in the batch.
 *
 * Deliberately overrides {@code callLlm} rather than {@code analyzeBatch} — everything
 * above the HTTP call is the real code, so prompt construction (including the
 * PERSONAL/EDITORIAL split), response parsing, batching and clone-collapsing all run
 * for real. Lives in this package because {@code callLlm} is package-private.
 */
public class PromptCapturingAnalyzer extends VacancyAiAnalyzer {

    public final List<String> prompts = new ArrayList<>();
    /** Verdict handed back for every id in the batch. */
    public String verdict = "yes";
    public int score = 85;

    public PromptCapturingAnalyzer(RuntimeConfig runtimeConfig, AiProviderManager providerManager, AiMetrics metrics) {
        super(runtimeConfig, providerManager, metrics);
    }

    @Override
    String callLlm(String prompt, int maxTokens) {
        prompts.add(prompt);
        return cannedResponse(prompt);
    }

    @Override
    String callLlm(String prompt, int maxTokens, String modelOverride) {
        return callLlm(prompt, maxTokens);
    }

    /** Builds an OpenAI-shaped response echoing one result per "ID: x" line in the prompt. */
    private String cannedResponse(String prompt) {
        StringBuilder items = new StringBuilder();
        for (String line : prompt.split("\n")) {
            if (!line.startsWith("ID: ")) continue;
            String id = line.substring(4).trim();
            if (!items.isEmpty()) items.append(",");
            items.append(String.format(
                "{\\\"id\\\":\\\"%s\\\",\\\"score\\\":%d,\\\"verdict\\\":\\\"%s\\\",\\\"reason\\\":\\\"подходит по сути задач\\\","
                    + "\\\"noveltyColor\\\":\\\"green\\\",\\\"noveltyNote\\\":\\\"разнообразные задачи\\\","
                    + "\\\"salaryFrom\\\":null,\\\"salaryTo\\\":null,\\\"currency\\\":null,\\\"company\\\":null,\\\"title\\\":null}",
                id, score, verdict));
        }
        return "{\"choices\":[{\"message\":{\"content\":\"[" + items + "]\"}}]}";
    }

    /** The single prompt sent, failing loudly if the pipeline never called the LLM at all. */
    public String onlyPrompt() {
        if (prompts.size() != 1) {
            throw new AssertionError("ожидался ровно один запрос к LLM, было: " + prompts.size());
        }
        return prompts.get(0);
    }
}
