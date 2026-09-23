package com.hh.gui.service;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Расписание очереди VK: посты уходят только в окна, не больше N за окно и не чаще, чем
 * раз в M минут. Часы подменяются — иначе тест зависел бы от того, во сколько его запустили.
 */
class VkPublishQueueTest {

    /** Репозиторий в памяти: очередь, отправленные, последняя отправка. */
    private static class FakeRepo extends VacancyRepository {
        final List<Vacancy> queued = new ArrayList<>();
        final List<String> sentAt = new ArrayList<>();
        final List<Long> failed = new ArrayList<>();
        FakeRepo() { super(null); }
        @Override public void enqueueForVk(List<Long> ids) { }
        @Override public List<Vacancy> findVkQueued(int limit) {
            return new ArrayList<>(queued.subList(0, Math.min(limit, queued.size())));
        }
        @Override public int expireVkQueue(String cutoffIso) { expireCutoff = cutoffIso; return 0; }
        @Override public void markVkSentBatch(List<Long> ids, String postId) {
            queued.removeIf(v -> ids.contains(v.getId()));
            sentAt.add(nowIso);   // один пост на всю подборку
            batches.add(List.copyOf(ids));
        }
        final List<List<Long>> batches = new ArrayList<>();
        String expireCutoff;
        @Override public int countVkQueued() { return queued.size(); }
        @Override public int countVkPublishedSince(String sinceIso) {
            return (int) sentAt.stream().filter(t -> t.compareTo(sinceIso) >= 0).count();
        }
        @Override public String lastVkPublishedAt() { return sentAt.isEmpty() ? null : sentAt.get(sentAt.size() - 1); }
        @Override public void markVkSent(Long id, String postId) {
            queued.removeIf(v -> v.getId().equals(id));
            sentAt.add(nowIso);
        }
        @Override public void markVkFailed(Long id) { failed.add(id); queued.removeIf(v -> v.getId().equals(id)); }
        String nowIso;
    }

    private static class FakeVk extends VkNotifier {
        final List<String> posts = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        boolean fail = false;
        final List<String> attachments = new ArrayList<>();
        @Override public Long postReturningId(String message) { return postReturningId(message, null); }
        @Override public Long postReturningId(String message, String attachment) {
            if (fail) return null; posts.add(message); attachments.add(attachment); return (long) posts.size();
        }
        @Override public String uploadWallPhoto(byte[] png, String fileName) { return "photo-1_" + fileName; }
        @Override public boolean comment(long postId, String text) { comments.add(text); return true; }
        @Override public String screenName() { return "remotevibe"; }
    }

    private static Vacancy vacancy(long id, String title) {
        Vacancy v = new Vacancy();
        v.setId(id); v.setHhId("h" + id); v.setTitle(title); v.setCompany("ООО Ромашка");
        v.setUrl("https://hh.ru/vacancy/" + id); v.setAiScore(80);
        return v;
    }

    private static Clock moscow(String localDateTime) {
        return Clock.fixed(Instant.from(java.time.LocalDateTime.parse(localDateTime).atZone(ZoneId.of("Europe/Moscow"))),
            ZoneId.of("UTC"));
    }

    private static RuntimeConfig config() {
        RuntimeConfig c = new RuntimeConfig();
        c.setVkEnabled(true);
        c.setVkPublishWindows("09:00,12:30,18:30");
        c.setVkPostsPerWindow(2);
        c.setVkMinGapMinutes(10);
        return c;
    }

    private static VkPublishQueue queue(FakeRepo repo, FakeVk vk, RuntimeConfig config, Clock clock) {
        repo.nowIso = clock.instant().toString();
        return new VkPublishQueue(repo, vk, config, new AiMetrics(new SimpleMeterRegistry(), config), clock);
    }

    @Test
    void beforeFirstWindow_nothingIsPublished() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        queue(repo, vk, config(), moscow("2026-09-21T08:59:00")).publishDue();
        assertTrue(vk.posts.isEmpty(), "до 09:00 по Москве окно закрыто");
    }

    @Test
    void insideWindow_publishesOneAndCommentsWithLink() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент руководителя"));
        FakeVk vk = new FakeVk();
        queue(repo, vk, config(), moscow("2026-09-21T09:05:00")).publishDue();
        assertEquals(1, vk.posts.size());
        assertTrue(vk.posts.get(0).startsWith("Ассистент руководителя — "), vk.posts.get(0));
        assertTrue(vk.posts.get(0).contains("#вакансии@remotevibe"), "сообщественный тег из screenName");
        assertEquals(List.of("Откликнуться: https://hh.ru/vacancy/1"), vk.comments, "ссылка — в первом комментарии");
        assertEquals("photo-1_vacancy-1.png", vk.attachments.get(0), "к посту приложена карточка");
    }

    @Test
    void cardsDisabled_postGoesWithoutAttachment() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkCardsEnabled(false);
        queue(repo, vk, config, moscow("2026-09-21T09:05:00")).publishDue();
        assertEquals(1, vk.posts.size());
        assertNull(vk.attachments.get(0));
    }

    @Test
    void windowLimit_stopsAfterConfiguredPosts() {
        FakeRepo repo = new FakeRepo();
        for (int i = 1; i <= 5; i++) repo.queued.add(vacancy(i, "Вакансия " + i));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config();
        config.setVkMinGapMinutes(1);
        // три тика с интервалом в 15 минут внутри утреннего окна
        for (String t : new String[]{"2026-09-21T09:00:00", "2026-09-21T09:15:00", "2026-09-21T09:30:00"}) {
            queue(repo, vk, config, moscow(t)).publishDue();
        }
        assertEquals(2, vk.posts.size(), "не больше vkPostsPerWindow за окно, остальное ждёт следующего");
        assertEquals(3, repo.queued.size());
    }

    @Test
    void minGap_secondPostWaits() {
        FakeRepo repo = new FakeRepo();
        repo.queued.add(vacancy(1, "Первая")); repo.queued.add(vacancy(2, "Вторая"));
        FakeVk vk = new FakeVk();
        queue(repo, vk, config(), moscow("2026-09-21T09:00:00")).publishDue();
        queue(repo, vk, config(), moscow("2026-09-21T09:03:00")).publishDue();   // прошло 3 мин < 10
        assertEquals(1, vk.posts.size(), "пауза между постами ещё не выдержана");
        queue(repo, vk, config(), moscow("2026-09-21T09:12:00")).publishDue();
        assertEquals(2, vk.posts.size());
    }

    @Test
    void newWindow_resetsTheCounter() {
        FakeRepo repo = new FakeRepo();
        for (int i = 1; i <= 4; i++) repo.queued.add(vacancy(i, "Вакансия " + i));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkMinGapMinutes(1);
        queue(repo, vk, config, moscow("2026-09-21T09:00:00")).publishDue();
        queue(repo, vk, config, moscow("2026-09-21T09:05:00")).publishDue();
        queue(repo, vk, config, moscow("2026-09-21T10:00:00")).publishDue();   // всё ещё утреннее окно — лимит
        assertEquals(2, vk.posts.size());
        queue(repo, vk, config, moscow("2026-09-21T12:31:00")).publishDue();   // обеденное окно — снова можно
        assertEquals(3, vk.posts.size());
    }

    @Test
    void vkDisabled_queueIsLeftAlone() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkEnabled(false);
        queue(repo, vk, config, moscow("2026-09-21T09:05:00")).publishDue();
        assertTrue(vk.posts.isEmpty());
        assertEquals(1, repo.queued.size(), "выключенный VK не должен терять очередь");
    }

    @Test
    void sendFailure_keepsVacancyQueued_andBacksOff() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk(); vk.fail = true;
        RuntimeConfig config = config();
        MutableClock clock = new MutableClock(moscow("2026-09-21T09:05:00").instant());
        VkPublishQueue q = new VkPublishQueue(repo, vk, config, new AiMetrics(new SimpleMeterRegistry(), config), clock);
        repo.nowIso = clock.instant().toString();
        q.publishDue();
        assertTrue(repo.failed.isEmpty(), "failed-статус раньше означал «потеряна навсегда» — enqueue новых id её не возвращал");
        assertEquals(1, repo.queued.size(), "вакансия осталась в очереди");
        assertTrue(vk.comments.isEmpty(), "без поста нет и комментария");

        vk.fail = false;
        clock.now = clock.now.plusSeconds(5 * 60);
        q.publishDue();
        assertTrue(vk.posts.isEmpty(), "до конца паузы после сбоя повторов нет");
        clock.now = clock.now.plusSeconds(11 * 60);
        repo.nowIso = clock.instant().toString();
        q.publishDue();
        assertEquals(1, vk.posts.size(), "после паузы вакансия уходит");
    }

    @Test
    void bigBacklog_goesOutAsDigestWithNumberedLinksInComment() {
        FakeRepo repo = new FakeRepo();
        for (int i = 1; i <= 30; i++) repo.queued.add(vacancy(i, "Вакансия " + i));
        FakeVk vk = new FakeVk();
        queue(repo, vk, config(), moscow("2026-09-21T09:05:00")).publishDue();

        assertEquals(1, vk.posts.size());
        // 30 в очереди, 6 постов на день: даже 3 витрины + 3 подборки по 7 не вмещают —
        // все посты становятся подборками по ceil(30/6)=5
        assertEquals(List.of(List.of(1L, 2L, 3L, 4L, 5L)), repo.batches);
        String post = vk.posts.get(0);
        assertTrue(post.startsWith("Утренняя подборка: 5 удалённых вакансий"), post);
        assertTrue(post.contains("1) Вакансия 1 — удалённо"), post);
        assertTrue(post.contains("5) Вакансия 5 — удалённо"), post);
        assertTrue(!post.contains("https://"), "ссылок в тексте нет — они в комментарии");
        String comment = vk.comments.get(0);
        assertTrue(comment.contains("1) https://hh.ru/vacancy/1"), comment);
        assertTrue(comment.contains("5) https://hh.ru/vacancy/5"), comment);
        assertEquals("photo-1_digest-1.png", vk.attachments.get(0), "у подборки своя карточка");
        assertEquals(25, repo.queued.size());
    }

    @Test
    void moderateBacklog_firstPostOfWindowIsShowcase_secondIsDigest() {
        FakeRepo repo = new FakeRepo();
        for (int i = 1; i <= 12; i++) repo.queued.add(vacancy(i, "Вакансия " + i));
        FakeVk vk = new FakeVk();
        queue(repo, vk, config(), moscow("2026-09-21T09:00:00")).publishDue();
        assertEquals(1, vk.posts.size());
        assertTrue(repo.batches.isEmpty(), "первый пост окна — одиночная витрина");
        assertEquals(11, repo.queued.size());

        queue(repo, vk, config(), moscow("2026-09-21T09:15:00")).publishDue();
        assertEquals(2, vk.posts.size());
        // осталось 11: впереди ещё 2 витрины и 3 подборки → (11-2)/3 = 3 в подборке
        assertEquals(1, repo.batches.size());
        assertEquals(3, repo.batches.get(0).size());
    }

    @Test
    void digestsDisabled_alwaysSinglePosts() {
        FakeRepo repo = new FakeRepo();
        for (int i = 1; i <= 30; i++) repo.queued.add(vacancy(i, "Вакансия " + i));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkDigestMaxSize(1);
        queue(repo, vk, config, moscow("2026-09-21T09:05:00")).publishDue();
        assertTrue(repo.batches.isEmpty());
        assertEquals(29, repo.queued.size());
    }

    @Test
    void staleQueueIsExpiredByConfiguredAge() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkQueueMaxAgeHours(48);
        Clock clock = moscow("2026-09-21T09:05:00");
        queue(repo, vk, config, clock).publishDue();
        assertEquals(clock.instant().minusSeconds(48 * 3600).toString(), repo.expireCutoff);
    }

    @Test
    void articleCountsTowardsWindowLimitAndGap() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config();
        Clock clock = moscow("2026-09-21T09:05:00");
        com.hh.gui.repository.VkArticleRepository articles = new com.hh.gui.repository.VkArticleRepository(null) {
            @Override public int countPublishedSince(String sinceIso) { return 0; }
            @Override public String lastPublishedAt() { return clock.instant().minusSeconds(120).toString(); }
            @Override public java.util.Optional<com.hh.gui.model.VkArticle> nextToPublish(String upToDate) { return java.util.Optional.empty(); }
        };
        repo.nowIso = clock.instant().toString();
        new VkPublishQueue(repo, vk, config, new AiMetrics(new SimpleMeterRegistry(), config), clock, articles).publishDue();
        assertTrue(vk.posts.isEmpty(), "статья вышла 2 минуты назад — пауза между постами касается и её");
    }

    @Test
    void planPost_scalesDigestToWhatIsLeftOfTheDay() {
        RuntimeConfig config = config();   // 3 окна по 2 поста, подборка до 7
        VkPublishQueue q = queue(new FakeRepo(), new FakeVk(), config, moscow("2026-09-21T09:05:00"));
        assertEquals(1, q.planPost(6, 0, 2), "хвост влезает в оставшиеся 6 постов — одиночные");
        assertEquals(1, q.planPost(12, 0, 2), "первый пост окна — витрина");
        assertEquals(7, q.planPost(220, 0, 2), "переполнение — подборки по максимуму");
        assertEquals(5, q.planPost(5, 1, 0), "последний пост дня забирает весь хвост");
        assertEquals(3, q.planPost(4, 1, 1), "подборка не меньше трёх");
    }

    /** Часы, которые тест двигает сам — для пауз, переживающих несколько тиков одного экземпляра. */
    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void brokenWindowSetting_isSkippedNotFatal() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkPublishWindows("09:00, кривое, 18:30");
        VkPublishQueue q = queue(repo, vk, config, moscow("2026-09-21T09:05:00"));
        assertEquals(2, q.windowStarts().size(), "нечитаемое окно пропущено, остальные работают");
        q.publishDue();
        assertEquals(1, vk.posts.size());
    }

    @Test
    void noWindowsConfigured_neverPublishes() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk();
        RuntimeConfig config = config(); config.setVkPublishWindows("");
        VkPublishQueue q = queue(repo, vk, config, moscow("2026-09-21T09:05:00"));
        assertNull(q.currentWindow());
        q.publishDue();
        assertTrue(vk.posts.isEmpty());
    }
}
