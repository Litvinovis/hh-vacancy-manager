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
        @Override public List<Vacancy> findVkQueued(int limit) { return queued.isEmpty() ? List.of() : List.of(queued.get(0)); }
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
    void sendFailure_marksFailed_doesNotBlockTheRest() {
        FakeRepo repo = new FakeRepo(); repo.queued.add(vacancy(1, "Ассистент"));
        FakeVk vk = new FakeVk(); vk.fail = true;
        queue(repo, vk, config(), moscow("2026-09-21T09:05:00")).publishDue();
        assertEquals(List.of(1L), repo.failed);
        assertTrue(vk.comments.isEmpty(), "без поста нет и комментария");
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
