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
        List<com.hh.gui.model.Vacancy> approved = new ArrayList<>();
        @Override public List<com.hh.gui.model.Vacancy> approvedSince(String s, int l) { return approved; }
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

    // ── обзоры по профессиям ──

    private static com.hh.gui.model.Vacancy vacancy(String title, String company, int salary, String reason) {
        com.hh.gui.model.Vacancy v = new com.hh.gui.model.Vacancy();
        v.setTitle(title); v.setCompany(company); v.setSalaryFrom(salary); v.setCurrency("RUR"); v.setAiReason(reason);
        return v;
    }

    private static VkArticle plannedSeo(String key) {
        VkArticle a = new VkArticle(); a.setId(7L); a.setTopicKey(key); a.setKind(SeoTopics.KIND); a.setPlannedFor("2026-09-28");
        a.setTitle(SeoTopics.byKey(key).headline());
        return a;
    }

    @Test
    void seo_promptHasHeadlineKeywordsAndFacts_postEndsWithCallToActionAndTags() {
        FakeAnalyzer ai = new FakeAnalyzer(); FakeArticles arts = new FakeArticles(); FakeVacancies vac = new FakeVacancies();
        for (int i = 0; i < 5; i++) vac.approved.add(vacancy("Ассистент руководителя " + i, "Ромашка", 60000 + i * 10000, "ведение календаря"));
        vac.approved.add(vacancy("Бухгалтер", "Счёт", 90000, "первичка"));   // не ассистент — в факты не идёт
        vac.approved.add(vacancy("Личный помощник", "Медведева Кристина Валерьевна", 50000, "переписка и звонки"));
        arts.toGenerate.add(plannedSeo("seo_assistant"));

        assertEquals(1, new ArticleGenerator(arts, vac, ai).generateDue("2026-09-28"));

        assertTrue(ai.lastPrompt.contains(SeoTopics.byKey("seo_assistant").headline()), ai.lastPrompt);
        assertTrue(ai.lastPrompt.contains("удаленная работа ассистентом"), ai.lastPrompt);
        assertTrue(ai.lastPrompt.contains("вакансий по этой профессии: 6"), ai.lastPrompt);
        assertTrue(ai.lastPrompt.contains("медиана"), ai.lastPrompt);
        assertTrue(!ai.lastPrompt.contains("Бухгалтер"), "чужая профессия в факты не попадает");
        assertTrue(!ai.lastPrompt.contains("Медведева"), "ФИО частного работодателя в промпт не несём");

        String body = arts.updated.get(0).getBody();
        assertTrue(body.contains(ArticleGenerator.SEO_CALL_TO_ACTION), body);
        assertTrue(body.endsWith("#удаленнаяработа #работанадому #ассистент"), body);
        assertTrue(!body.contains("#удалённаяработа"), "хэштеги модели срезаны, чтобы не было дублей: " + body);
    }

    @Test
    void seo_tooFewVacancies_markedFailedWithoutCallingModel() {
        FakeAnalyzer ai = new FakeAnalyzer(); FakeArticles arts = new FakeArticles(); FakeVacancies vac = new FakeVacancies();
        vac.approved.add(vacancy("Бухгалтер", "Счёт", 90000, "первичка"));
        arts.toGenerate.add(plannedSeo("seo_accountant"));

        assertEquals(0, new ArticleGenerator(arts, vac, ai).generateDue("2026-09-28"));
        assertEquals("failed", arts.updated.get(0).getStatus());
        assertEquals(null, ai.lastPrompt);
    }

    @Test
    void seo_garbledModelText_notSaved_retriedLater() {
        FakeAnalyzer ai = new FakeAnalyzer(); ai.reply = "Аналитика и数字-управление " + "х".repeat(400);
        FakeArticles arts = new FakeArticles(); FakeVacancies vac = new FakeVacancies();
        for (int i = 0; i < 5; i++) vac.approved.add(vacancy("Менеджер Wildberries", "Бренд", 80000, "реклама"));
        arts.toGenerate.add(plannedSeo("seo_marketplace"));

        assertEquals(0, new ArticleGenerator(arts, vac, ai).generateDue("2026-09-28"));
        assertTrue(arts.updated.isEmpty());
    }
}
