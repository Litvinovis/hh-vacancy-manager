package com.hh.gui.content;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.VkArticleRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPlannerTest {

    private static class FakeArticles extends VkArticleRepository {
        final List<VkArticle> saved = new ArrayList<>();
        final Set<String> usedRecently = new HashSet<>();
        int alreadyPlanned = 0;
        FakeArticles() { super(null); }
        @Override public VkArticle save(VkArticle a) { saved.add(a); return a; }
        @Override public Set<String> topicsUsedSince(String sinceDate) { return new HashSet<>(usedRecently); }
        @Override public int countPlannedFrom(String fromDate) { return alreadyPlanned; }
    }

    private static Clock at(String isoLocalMoscow) {
        return Clock.fixed(Instant.from(java.time.LocalDateTime.parse(isoLocalMoscow).atZone(ZoneId.of("Europe/Moscow"))), ZoneId.of("UTC"));
    }

    private static RuntimeConfig config(int articles, int polls, String days) {
        RuntimeConfig c = new RuntimeConfig();
        c.setVkArticlesPerWeek(articles); c.setVkPollsPerWeek(polls); c.setVkContentDays(days);
        return c;
    }

    @Test
    void plansArticlesAndPolls_dataBackedTopicsFirst() {
        FakeArticles repo = new FakeArticles();
        // понедельник 21.09.2026, 08:00 МСК
        new ContentPlanner(repo, config(2, 1, "TUE,THU,SAT"), at("2026-09-21T08:00:00")).planCurrentWeek();

        assertEquals(3, repo.saved.size());
        List<String> keys = repo.saved.stream().map(VkArticle::getTopicKey).toList();
        assertTrue(ContentTopics.byKey(keys.get(0)).dataBacked(), "первой берётся тема на данных: " + keys);
        assertTrue(ContentTopics.byKey(keys.get(1)).dataBacked(), keys.toString());
        assertEquals("poll", repo.saved.get(2).getKind());
        assertEquals(List.of("2026-09-22", "2026-09-24", "2026-09-26"),
            repo.saved.stream().map(VkArticle::getPlannedFor).toList(), "вт, чт, сб");
    }

    @Test
    void recentlyUsedTopics_areSkipped() {
        FakeArticles repo = new FakeArticles();
        repo.usedRecently.addAll(List.of("fraud_signs", "top_professions_week", "salary_snapshot", "week_digest", "rejected_reasons"));
        new ContentPlanner(repo, config(2, 0, "TUE,THU"), at("2026-09-21T08:00:00")).planCurrentWeek();

        List<String> keys = repo.saved.stream().map(VkArticle::getTopicKey).toList();
        assertEquals(2, keys.size());
        for (String k : keys) {
            assertFalse(repo.usedRecently.contains(k), "тема в cooldown взята повторно: " + k);
            assertFalse(ContentTopics.byKey(k).dataBacked(), "все темы на данных в cooldown — берём общие");
        }
    }

    @Test
    void weekAlreadyPlanned_isIdempotent() {
        FakeArticles repo = new FakeArticles();
        repo.alreadyPlanned = 3;
        int created = new ContentPlanner(repo, config(2, 1, "TUE,THU,SAT"), at("2026-09-23T10:00:00")).planCurrentWeek();
        assertEquals(0, created);
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void midWeekStart_skipsDaysAlreadyPassed() {
        FakeArticles repo = new FakeArticles();
        // среда — вторник уже прошёл, остаются чт и сб; три записи ложатся на два дня по кругу
        new ContentPlanner(repo, config(2, 1, "TUE,THU,SAT"), at("2026-09-23T10:00:00")).planCurrentWeek();
        List<String> dates = repo.saved.stream().map(VkArticle::getPlannedFor).toList();
        assertEquals(List.of("2026-09-24", "2026-09-26", "2026-09-24"), dates);
    }

    @Test
    void unknownDayName_isSkippedNotFatal() {
        ContentPlanner p = new ContentPlanner(new FakeArticles(), config(1, 0, "TUE, среда, SAT"), at("2026-09-21T08:00:00"));
        assertEquals(2, p.contentDays(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 21)).size());
    }

    @Test
    void topicCatalogue_keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (ContentTopics.Topic t : ContentTopics.ALL) {
            assertTrue(keys.add(t.key()), "дублирующийся ключ темы: " + t.key());
        }
    }
}
