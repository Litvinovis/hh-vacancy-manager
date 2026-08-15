package com.hh.gui.service;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publishing behaviour, exercised directly rather than through the whole pipeline.
 *
 * These cases used to live in VacancyPipelineServiceTest and reach their subjects by
 * reflection (getDeclaredMethod + setAccessible), because the methods were private to
 * a class that also owned discovery, scraping and AI analysis. Now that publishing is
 * its own collaborator with five dependencies, they are plain calls.
 */
class ChannelPublisherTest {

    private static final long SEARCH_ID = 10L;

    // ── builder ──

    private static Builder publisher() { return new Builder(); }

    private static final class Builder {
        private VacancyRepository repo = new VacancyRepository(null);
        private SearchRepository searchRepo;
        private TelegramNotifier notifier = new TelegramNotifier();
        private RuntimeConfig config = new RuntimeConfig();
        private SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Builder repo(VacancyRepository v) { this.repo = v; return this; }
        Builder searchRepo(SearchRepository v) { this.searchRepo = v; return this; }
        Builder notifier(TelegramNotifier v) { this.notifier = v; return this; }
        Builder config(RuntimeConfig v) { this.config = v; return this; }

        ChannelPublisher build() {
            return new ChannelPublisher(repo, searchRepo, notifier, new TelegramMetrics(registry), config);
        }
    }

    private static SearchJob job() {
        SearchJob j = new SearchJob();
        j.personName = "Все пользователи";
        j.searchName = "Без техстека";
        j.searchId = SEARCH_ID;
        j.isGlobal = true;
        j.chatId = "-100123";
        return j;
    }

    private static Vacancy vacancy(long id, String hhId, String title) {
        Vacancy v = new Vacancy();
        v.setId(id);
        v.setHhId(hhId);
        v.setTitle(title);
        v.setCompany("@testchan");
        v.setSearchId(SEARCH_ID);
        v.setAiScore(80);
        v.setUrl("https://t.me/testchan/" + id);
        return v;
    }

    // ── fakes ──

    /** Captures enqueuePublish; findQueueTailTime/countQueued track real state across calls,
     *  which a stateless "always empty" fake cannot — see the merge regression below. */
    private static class FakeQueueRepo extends VacancyRepository {
        final List<Long> enqueuedIds = new ArrayList<>();
        final List<String> enqueuedPublishAts = new ArrayList<>();
        FakeQueueRepo() { super(null); }
        @Override
        public Optional<String> findQueueTailTime(Long searchId) {
            return enqueuedPublishAts.isEmpty() ? Optional.empty()
                : Optional.of(enqueuedPublishAts.get(enqueuedPublishAts.size() - 1));
        }
        @Override
        public int countQueued(Long searchId) { return enqueuedIds.size(); }
        @Override
        public void enqueuePublish(List<Long> ids, List<String> publishAts) {
            enqueuedIds.addAll(ids);
            enqueuedPublishAts.addAll(publishAts);
        }
    }

    private static class FakeDueQueueRepo extends VacancyRepository {
        List<Vacancy> due = new ArrayList<>();
        final List<Long> notifiedIds = new ArrayList<>();
        FakeDueQueueRepo() { super(null); }
        @Override
        public List<Vacancy> findDueQueuedPublications(String nowIso, int limit) { return due; }
        @Override
        public void markNotified(List<Long> ids) { notifiedIds.addAll(ids); }
    }

    private static class FakeSearchRepo extends SearchRepository {
        final Map<Long, SearchConfig> byId = new java.util.HashMap<>();
        FakeSearchRepo() { super(null); }
        @Override
        public Optional<SearchConfig> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
    }

    private static FakeSearchRepo searchRepoWithChat(String chatId) {
        FakeSearchRepo r = new FakeSearchRepo();
        SearchConfig s = new SearchConfig();
        s.setId(SEARCH_ID);
        s.setChatId(chatId);
        r.byId.put(SEARCH_ID, s);
        return r;
    }

    private static class RecordingChannelBotNotifier extends TelegramNotifier {
        final List<String> sentMessages = new ArrayList<>();
        boolean sendResult = true;
        @Override
        public boolean sendViaChannelBot(String message, String targetChatId) {
            if (sendResult) sentMessages.add(message);
            return sendResult;
        }
    }

    // ── dynamicPaceMinutes ──

    @Test
    void dynamicPaceMinutes_emptyQueue_usesBasePace() {
        assertEquals(5, ChannelPublisher.dynamicPaceMinutes(5, 0));
    }

    @Test
    void dynamicPaceMinutes_deepQueue_shortensInterval() {
        assertEquals(4, ChannelPublisher.dynamicPaceMinutes(5, 6),
            "5*5/6 = 4.16 -> округление вниз до 4, короче базовых 5 мин");
    }

    @Test
    void dynamicPaceMinutes_shallowQueue_lengthensInterval() {
        assertEquals(25, ChannelPublisher.dynamicPaceMinutes(5, 1));
    }

    @Test
    void dynamicPaceMinutes_extremelyDeepQueue_neverBelowMinPace() {
        assertEquals(3, ChannelPublisher.dynamicPaceMinutes(5, 10_000),
            "не должен уходить ниже MIN_PACE_MINUTES независимо от размера очереди");
    }

    @Test
    void dynamicPaceMinutes_extremelyShallowQueue_neverAboveMaxPace() {
        assertEquals(60, ChannelPublisher.dynamicPaceMinutes(100, 1),
            "не должен уходить выше MAX_PACE_MINUTES независимо от базового темпа");
    }

    // ── ночное окно ──

    @Test
    void pushPastNightWindow_daytimeInstant_unchanged() {
        Instant daytime = ZonedDateTime.now().withHour(14).withMinute(0).withSecond(0).withNano(0).toInstant();
        assertEquals(daytime, ChannelPublisher.pushPastNightWindow(daytime));
    }

    @Test
    void pushPastNightWindow_earlyMorningInstant_pushedToWindowStartSameDay() {
        Instant earlyMorning = ZonedDateTime.now().withHour(3).withMinute(30).withSecond(0).withNano(0).toInstant();
        Instant result = ChannelPublisher.pushPastNightWindow(earlyMorning);
        ZonedDateTime zdt = result.atZone(java.time.ZoneId.systemDefault());
        assertEquals(7, zdt.getHour());
        assertEquals(0, zdt.getMinute());
        assertEquals(earlyMorning.atZone(java.time.ZoneId.systemDefault()).toLocalDate(), zdt.toLocalDate());
    }

    @Test
    void pushPastNightWindow_lateEveningInstant_pushedToWindowStartNextDay() {
        Instant lateEvening = ZonedDateTime.now().withHour(23).withMinute(30).withSecond(0).withNano(0).toInstant();
        Instant result = ChannelPublisher.pushPastNightWindow(lateEvening);
        ZonedDateTime zdt = result.atZone(java.time.ZoneId.systemDefault());
        assertEquals(7, zdt.getHour());
        assertEquals(lateEvening.atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(1), zdt.toLocalDate());
    }

    // ── постановка в очередь ──

    @Test
    void enqueue_batchesFiveVacanciesPerDueTime() {
        FakeQueueRepo repo = new FakeQueueRepo();
        ChannelPublisher publisher = publisher().repo(repo).build();
        SearchJob job = job();
        job.publishPaceMinutes = 5;

        List<Vacancy> approved = new ArrayList<>();
        for (int i = 0; i < 12; i++) approved.add(vacancy(i + 1, "tg_testchan_" + (i + 1), "Вакансия " + (i + 1)));

        publisher.send(approved, job);

        assertEquals(12, repo.enqueuedIds.size());
        assertEquals(3, new HashSet<>(repo.enqueuedPublishAts).size(),
            "12 вакансий по 5 в батче -> 3 разных момента публикации (5+5+2)");
    }

    @Test
    void enqueue_secondCallWithNonEmptyQueue_startsNewBatchInsteadOfMerging() {
        // Live bug (fixed 2026-08-15): with a standing backlog — the normal case, where the
        // queue's tail already sits hours in the future — a SECOND call of 5 used to merge
        // into the FIRST call's still-open batch, because the batch boundary was computed
        // from the local loop index instead of the global queue position. Seeding a
        // future-dated batch is essential: enqueue falls back to Instant.now() whenever the
        // tail isn't already in the future, which would mask the bug entirely.
        FakeQueueRepo repo = new FakeQueueRepo();
        ZonedDateTime midWindow = ZonedDateTime.now().withHour(14).withMinute(0).withSecond(0).withNano(0);
        if (!midWindow.isAfter(ZonedDateTime.now())) midWindow = midWindow.plusDays(1);
        String existingTail = midWindow.toInstant().toString();
        repo.enqueuedIds.addAll(List.of(101L, 102L, 103L, 104L, 105L));
        for (int i = 0; i < 5; i++) repo.enqueuedPublishAts.add(existingTail);

        ChannelPublisher publisher = publisher().repo(repo).build();
        SearchJob job = job();
        job.publishPaceMinutes = 5;

        List<Vacancy> newBatch = new ArrayList<>();
        for (int i = 0; i < 5; i++) newBatch.add(vacancy(i + 1, "tg_testchan_" + (i + 1), "Вакансия " + (i + 1)));
        publisher.send(newBatch, job);

        List<String> newTimes = repo.enqueuedPublishAts.subList(5, 10);
        assertEquals(1, new HashSet<>(newTimes).size(), "новые 5 должны разделять один момент публикации между собой");
        assertNotEquals(existingTail, newTimes.get(0),
            "новый батч из 5 не должен слиться с уже стоящим в очереди батчем — у него должен быть свой, более поздний момент");
    }

    // ── отправка из очереди ──

    @Test
    void publishDueQueued_combinesDueVacanciesIntoOneMessage_notOnePerVacancy() {
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        repo.due = List.of(
            vacancy(1, "tg_testchan_1", "Оператор чата"),
            vacancy(2, "tg_testchan_2", "Ассистент руководителя"),
            vacancy(3, "tg_testchan_3", "Менеджер маркетплейса"));
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);

        publisher().repo(repo).searchRepo(searchRepoWithChat("-100999")).notifier(notifier).config(config)
            .build().doPublishDueQueued(50);

        assertEquals(1, notifier.sentMessages.size(), "три вакансии должны уйти ОДНИМ сообщением, а не тремя");
        String sent = notifier.sentMessages.get(0);
        assertTrue(sent.contains("Оператор чата"), sent);
        assertTrue(sent.contains("Ассистент руководителя"), sent);
        assertTrue(sent.contains("Менеджер маркетплейса"), sent);
        assertEquals(List.of(1L, 2L, 3L), repo.notifiedIds);
    }

    @Test
    void publishDueQueued_moreThanBatchSizeDue_onlyFirstBatchSentThisTick() {
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        List<Vacancy> six = new ArrayList<>();
        for (int i = 1; i <= 6; i++) six.add(vacancy(i, "tg_testchan_" + i, "Вакансия " + i));
        repo.due = six;
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);

        publisher().repo(repo).searchRepo(searchRepoWithChat("-100999")).notifier(notifier).config(config)
            .build().doPublishDueQueued(50);

        assertEquals(1, notifier.sentMessages.size());
        assertEquals(5, repo.notifiedIds.size(), "не больше PUBLISH_BATCH_SIZE=5 вакансий за один тик");
        assertFalse(notifier.sentMessages.get(0).contains("Вакансия 6"));
    }

    @Test
    void publishDueQueued_sendFails_nothingMarkedNotified() {
        // All-or-nothing: one Telegram message can't partially succeed, so a failed send
        // must leave the whole batch for the next tick rather than silently losing it.
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        repo.due = List.of(vacancy(1, "tg_testchan_1", "Оператор чата"));
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        notifier.sendResult = false;
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);

        publisher().repo(repo).searchRepo(searchRepoWithChat("-100999")).notifier(notifier).config(config)
            .build().doPublishDueQueued(50);

        assertTrue(repo.notifiedIds.isEmpty(), "неудачная отправка не должна помечать вакансии как отправленные");
    }

    @Test
    void publishDueQueued_searchWithoutChatId_skippedWithoutSending() {
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        repo.due = List.of(vacancy(1, "tg_testchan_1", "Оператор чата"));
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(true);

        publisher().repo(repo).searchRepo(searchRepoWithChat("")).notifier(notifier).config(config)
            .build().doPublishDueQueued(50);

        assertTrue(notifier.sentMessages.isEmpty());
        assertTrue(repo.notifiedIds.isEmpty(), "без адреса назначения строки должны остаться в очереди, а не потеряться");
    }

    @Test
    void publishDueQueued_channelNotificationsOff_doesNothing() {
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        repo.due = List.of(vacancy(1, "tg_testchan_1", "Оператор чата"));
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        RuntimeConfig config = new RuntimeConfig();
        config.setChannelNotificationsEnabled(false);

        publisher().repo(repo).searchRepo(searchRepoWithChat("-100999")).notifier(notifier).config(config)
            .build().publishDueQueued(50);

        assertTrue(notifier.sentMessages.isEmpty());
    }

    // ── немедленная отправка ──

    @Test
    void send_withoutPace_sendsOnePostPerVacancy() {
        FakeDueQueueRepo repo = new FakeDueQueueRepo();
        RecordingChannelBotNotifier notifier = new RecordingChannelBotNotifier();
        ChannelPublisher publisher = publisher().repo(repo).notifier(notifier).build();
        SearchJob job = job();
        job.publishPaceMinutes = null;

        publisher.send(List.of(vacancy(1, "tg_testchan_1", "Первая"), vacancy(2, "tg_testchan_2", "Вторая")), job);

        assertEquals(2, notifier.sentMessages.size(), "без темпа публикации каждая вакансия уходит своим постом");
        assertEquals(List.of(1L, 2L), repo.notifiedIds);
    }

    @Test
    void formatBatch_separatesEntriesWithDivider() {
        ChannelPublisher publisher = publisher().build();
        String out = publisher.formatBatch(List.of(
            vacancy(1, "tg_testchan_1", "Первая"), vacancy(2, "tg_testchan_2", "Вторая")));
        assertTrue(out.contains("➖➖➖➖➖"), out);
        assertTrue(out.indexOf("Первая") < out.indexOf("Вторая"), "порядок вакансий должен сохраняться");
    }
}
