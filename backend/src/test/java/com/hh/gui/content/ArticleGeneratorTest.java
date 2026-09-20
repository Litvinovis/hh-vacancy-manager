package com.hh.gui.content;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.repository.VkArticleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleGeneratorTest {

    private static class FakeAnalyzer extends VacancyAiAnalyzer {
        String lastPrompt; String reply = "Первая строка-хук.\n\n" + "х".repeat(400) + "\n\nА вы как думаете?\n#удалённаяработа #обман";
        FakeAnalyzer() { super(null, null, null, null); }
        @Override public String generateText(String prompt, int maxTokens) { lastPrompt = prompt; return reply; }
    }
    private static class FakeVacancies extends VacancyRepository {
        FakeVacancies() { super(null); }
        @Override public List<String> recentFraudReasons(int limit) { return List.of("оплата обучения перед стартом", "работа под чужими аккаунтами"); }
        @Override public Map<String, Integer> topTitlesSince(String s, int l) { return Map.of("Ассистент", 12); }
    }
    private static class FakeArticles extends VkArticleRepository {
        final List<VkArticle> toGenerate = new ArrayList<>(); final List<VkArticle> updated = new ArrayList<>();
        FakeArticles() { super(null); }
        @Override public List<VkArticle> findToGenerate(String upToDate) { return toGenerate; }
        @Override public void update(VkArticle a) { updated.add(a); }
    }

    private static VkArticle planned(String key, String kind) {
        VkArticle a = new VkArticle(); a.setId(1L); a.setTopicKey(key); a.setKind(kind); a.setPlannedFor("2026-09-22");
        a.setTitle(ContentTopics.byKey(key).title());
        return a;
    }

    @Test
    void dataBackedTopic_promptCarriesRealFacts_andBodyIsSaved() {
        FakeAnalyzer ai = new FakeAnalyzer(); FakeArticles arts = new FakeArticles();
        arts.toGenerate.add(planned("fraud_signs", "article"));
        int done = new ArticleGenerator(arts, new FakeVacancies(), ai).generateDue("2026-09-22");

        assertEquals(1, done);
        assertTrue(ai.lastPrompt.contains("оплата обучения перед стартом"), "факты из базы должны попасть в промпт");
        assertTrue(ai.lastPrompt.contains("ничего не выдумывай"));
        assertEquals("generated", arts.updated.get(0).getStatus());
        assertTrue(arts.updated.get(0).getBody().startsWith("Первая строка-хук."));
    }

    @Test
    void modelReturnsNothing_articleStaysPlannedForRetry() {
        FakeAnalyzer ai = new FakeAnalyzer(); ai.reply = null;
        FakeArticles arts = new FakeArticles(); arts.toGenerate.add(planned("resume_remote", "article"));
        int done = new ArticleGenerator(arts, new FakeVacancies(), ai).generateDue("2026-09-22");
        assertEquals(0, done);
        assertTrue(arts.updated.isEmpty(), "провал модели не должен помечать статью failed — попробуем позже");
    }

    @Test
    void poll_needsNoModel_becomesReadyImmediately() {
        FakeAnalyzer ai = new FakeAnalyzer(); FakeArticles arts = new FakeArticles();
        arts.toGenerate.add(planned("poll_sphere", "poll"));
        new ArticleGenerator(arts, new FakeVacancies(), ai).generateDue("2026-09-22");
        assertEquals("generated", arts.updated.get(0).getStatus());
        assertEquals(null, ai.lastPrompt, "опросу текст модели не нужен");
    }

    @Test
    void generalTopic_promptHasNoFactsBlock() {
        FakeAnalyzer ai = new FakeAnalyzer(); FakeArticles arts = new FakeArticles();
        arts.toGenerate.add(planned("resume_remote", "article"));
        new ArticleGenerator(arts, new FakeVacancies(), ai).generateDue("2026-09-22");
        assertTrue(!ai.lastPrompt.contains("ФАКТЫ ИЗ НАШЕЙ БАЗЫ"));
        assertTrue(ai.lastPrompt.contains("ровно два хэштега"));
    }
}
