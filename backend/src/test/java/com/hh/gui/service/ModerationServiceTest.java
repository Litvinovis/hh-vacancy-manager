package com.hh.gui.service;

import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModerationService is the manual gate between AI approval and the public channel —
 * one Telegram card at a time, no timeout, resolved by ModerationBotPoller's
 * callback_query handling. These tests exercise the queue-advance logic and the two
 * resolutions directly against fakes, since the real collaborators (repository,
 * ChannelPublisher, TelegramNotifier) all talk to external systems.
 */
class ModerationServiceTest {

    private static class FakeVacancyRepo extends VacancyRepository {
        final List<Long> queuedCalls = new ArrayList<>();
        final List<Long> sentMarks = new ArrayList<>();
        final List<Long> approvedMarks = new ArrayList<>();
        final List<Long> rejectedMarks = new ArrayList<>();
        Optional<Vacancy> currentSent = Optional.empty();
        Optional<Vacancy> nextQueued = Optional.empty();
        final Map<Long, Vacancy> byId = new HashMap<>();

        FakeVacancyRepo() { super(null); }
        @Override public void markModerationQueued(List<Long> ids) { queuedCalls.addAll(ids); }
        @Override public Optional<Vacancy> findCurrentSentForModeration() { return currentSent; }
        @Override public Optional<Vacancy> findNextQueuedForModeration() { return nextQueued; }
        @Override public void markModerationSent(Long id) { sentMarks.add(id); }
        @Override public void markModerationApproved(Long id) { approvedMarks.add(id); }
        @Override public void markModerationRejected(Long id) { rejectedMarks.add(id); }
        @Override public Optional<Vacancy> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
    }

    private static class FakeProfileFactory extends SearchProfileFactory {
        final Map<Long, SearchJob> jobs = new HashMap<>();
        FakeProfileFactory() { super(null, null); }
        @Override public Optional<SearchJob> buildForSearchId(Long searchId) {
            return Optional.ofNullable(jobs.get(searchId));
        }
    }

    private static class RecordingChannelPublisher extends ChannelPublisher {
        final List<Vacancy> sentVacancies = new ArrayList<>();
        SearchJob sentJob;
        RecordingChannelPublisher() { super(null, null, null, null, null); }
        @Override
        void send(List<Vacancy> approved, SearchJob job) {
            sentVacancies.addAll(approved);
            sentJob = job;
        }
    }

    private static class RecordingNotifier extends TelegramNotifier {
        final List<String> sentCards = new ArrayList<>();
        final List<Long> sentVacancyIds = new ArrayList<>();
        boolean sendResult = true;
        @Override
        public boolean sendModerationCard(String message, long vacancyId) {
            sentCards.add(message);
            sentVacancyIds.add(vacancyId);
            return sendResult;
        }
    }

    private FakeVacancyRepo repo = new FakeVacancyRepo();
    private FakeProfileFactory profileFactory = new FakeProfileFactory();
    private RecordingChannelPublisher publisher = new RecordingChannelPublisher();
    private RecordingNotifier notifier = new RecordingNotifier();
    private ModerationService service = new ModerationService(repo, profileFactory, publisher, notifier);

    private Vacancy vacancy(long id, Long searchId, String title) {
        Vacancy v = new Vacancy();
        v.setId(id);
        v.setHhId(String.valueOf(id));
        v.setSearchId(searchId);
        v.setTitle(title);
        v.setUrl("https://hh.ru/vacancy/" + id);
        // Realistic precondition: advanceQueue put it here right before the card went
        // out, and that's the only state resolveApprove/resolveReject are meant to act
        // on (see alreadySent) — tests that specifically exercise the replay guard
        // override this explicitly.
        v.setModerationStatus("sent");
        return v;
    }

    private SearchJob job(String searchName, Long searchId) {
        SearchJob j = new SearchJob();
        j.personName = "Все пользователи";
        j.searchName = searchName;
        j.searchId = searchId;
        j.chatId = "-100123";
        return j;
    }

    @Test
    void queueForModeration_marksEachVacancyQueued() {
        service.queueForModeration(List.of(vacancy(1, 10L, "A"), vacancy(2, 10L, "B")), job("Без техстека", 10L));
        assertEquals(List.of(1L, 2L), repo.queuedCalls);
    }

    @Test
    void queueForModeration_emptyList_doesNothing() {
        service.queueForModeration(List.of(), job("Без техстека", 10L));
        assertTrue(repo.queuedCalls.isEmpty());
    }

    @Test
    void advanceQueue_somethingAlreadySent_doesNotSendAnotherCard() {
        repo.currentSent = Optional.of(vacancy(1, 10L, "В процессе"));
        repo.nextQueued = Optional.of(vacancy(2, 10L, "Ждёт"));

        service.advanceQueue();

        assertTrue(notifier.sentCards.isEmpty(), "нельзя показывать вторую карточку, пока не решена первая");
        assertTrue(repo.sentMarks.isEmpty());
    }

    @Test
    void advanceQueue_nothingSentAndQueueEmpty_doesNothing() {
        service.advanceQueue();
        assertTrue(notifier.sentCards.isEmpty());
    }

    @Test
    void advanceQueue_nothingSentQueueHasItem_sendsCardAndMarksSent() {
        repo.nextQueued = Optional.of(vacancy(5, 10L, "Оператор поддержки"));

        service.advanceQueue();

        assertEquals(1, notifier.sentCards.size());
        assertTrue(notifier.sentCards.get(0).contains("Оператор поддержки"));
        assertEquals(List.of(5L), notifier.sentVacancyIds);
        assertEquals(List.of(5L), repo.sentMarks);
    }

    @Test
    void advanceQueue_sendFails_doesNotMarkSent() {
        notifier.sendResult = false;
        repo.nextQueued = Optional.of(vacancy(5, 10L, "Оператор поддержки"));

        service.advanceQueue();

        assertTrue(repo.sentMarks.isEmpty(), "неудачная отправка не должна считаться показанной карточкой");
    }

    @Test
    void resolveApprove_publishesViaChannelPublisherWithReconstructedJob() {
        Vacancy v = vacancy(7, 10L, "Аналитик");
        repo.byId.put(7L, v);
        SearchJob reconstructed = job("Без техстека", 10L);
        profileFactory.jobs.put(10L, reconstructed);

        service.resolveApprove(7L);

        assertEquals(List.of(7L), repo.approvedMarks);
        assertEquals(List.of(v), publisher.sentVacancies);
        assertSame(reconstructed, publisher.sentJob);
        assertTrue(repo.rejectedMarks.isEmpty());
    }

    @Test
    void resolveApprove_vacancyNotFound_doesNotThrowOrPublish() {
        assertDoesNotThrow(() -> service.resolveApprove(999L));
        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveApprove_searchNoLongerExists_rejectsInsteadOfPublishing() {
        // Search deleted/disabled since this vacancy was queued — nothing sane to publish
        // to, same fail-open philosophy as ChannelPublisher's own missing-chatId guards.
        Vacancy v = vacancy(8, 999L, "Пропавший поиск");
        repo.byId.put(8L, v);

        service.resolveApprove(8L);

        assertEquals(List.of(8L), repo.rejectedMarks);
        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveApprove_advancesQueueAfterward() {
        Vacancy approved = vacancy(7, 10L, "Аналитик");
        repo.byId.put(7L, approved);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));
        repo.nextQueued = Optional.of(vacancy(9, 10L, "Следующая в очереди"));

        service.resolveApprove(7L);

        assertEquals(1, notifier.sentCards.size(), "после решения должна показаться следующая карточка");
        assertTrue(notifier.sentCards.get(0).contains("Следующая в очереди"));
    }

    @Test
    void resolveReject_marksRejectedAndAdvancesQueue() {
        repo.nextQueued = Optional.of(vacancy(9, 10L, "Следующая в очереди"));

        service.resolveReject(4L);

        assertEquals(List.of(4L), repo.rejectedMarks);
        assertTrue(publisher.sentVacancies.isEmpty());
        assertEquals(1, notifier.sentCards.size());
    }

    // ── Replay guard: a Telegram callback_query is redelivered until the offset
    //    advances past it, and the poller's offset resets to 0 on every app restart
    //    unless persisted — see ModerationBotPoller. A stale/duplicate tap must never
    //    re-publish or re-resolve something already decided. ──

    @Test
    void resolveApprove_vacancyAlreadyApproved_doesNotPublishAgain() {
        Vacancy alreadyApproved = vacancy(7, 10L, "Уже одобрена");
        alreadyApproved.setModerationStatus("approved"); // not 'sent' — replay of an old tap
        repo.byId.put(7L, alreadyApproved);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L);

        assertTrue(publisher.sentVacancies.isEmpty(), "повторный approve не должен публиковать вакансию второй раз");
        assertTrue(repo.approvedMarks.isEmpty(), "moderation_status уже 'approved' — повторно помечать не нужно");
    }

    @Test
    void resolveApprove_vacancyAlreadyRejected_doesNotPublish() {
        Vacancy alreadyRejected = vacancy(7, 10L, "Уже отклонена");
        alreadyRejected.setModerationStatus("rejected");
        repo.byId.put(7L, alreadyRejected);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L);

        assertTrue(publisher.sentVacancies.isEmpty(),
            "просроченный approve на уже отклонённую вакансию не должен всё равно публиковать её");
    }

    @Test
    void resolveApprove_vacancyStillQueuedNotYetSent_doesNotSkipAhead() {
        // Defensive edge case: a tap can't legitimately arrive for a card that hasn't
        // been sent yet, but if it somehow does (clock skew, manual DB edit), don't
        // publish something the owner never actually saw.
        Vacancy stillQueued = vacancy(7, 10L, "Ещё не отправлена");
        stillQueued.setModerationStatus("queued");
        repo.byId.put(7L, stillQueued);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L);

        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveReject_vacancyAlreadyApproved_doesNotOverwriteDecision() {
        Vacancy alreadyApproved = vacancy(7, 10L, "Уже одобрена");
        alreadyApproved.setModerationStatus("approved");
        repo.byId.put(7L, alreadyApproved);

        service.resolveReject(7L);

        assertTrue(repo.rejectedMarks.isEmpty(),
            "просроченный reject не должен задним числом отменять уже состоявшуюся публикацию");
    }
}
