package com.hh.gui.service;

import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModerationService is the manual gate between AI approval and the public channel —
 * up to MODERATION_BATCH_SIZE vacancies grouped into one Telegram card, no timeout,
 * resolved by ModerationBotPoller's callback_query handling. These tests exercise the
 * queue-advance logic and the three resolutions (approve one / reject one / approve
 * all) directly against a fake repository reactive enough to track real moderation_status
 * transitions — same real collaborators (repository, ChannelPublisher, TelegramNotifier)
 * all talk to external systems in production, so they're faked here too.
 */
class ModerationServiceTest {

    /** Reactive: moderation_status transitions actually happen on the stored Vacancy
     *  objects, same as a real UPDATE would — findAllSentForModeration/
     *  findNextQueuedBatchForModeration compute from that state, not a separately-set
     *  test fixture, so a test can't accidentally assert against state the code under
     *  test never actually produced. */
    private static class FakeVacancyRepo extends VacancyRepository {
        final List<Long> queuedCalls = new ArrayList<>();
        final List<Long> sentMarks = new ArrayList<>();
        final List<Long> approvedMarks = new ArrayList<>();
        final List<Long> rejectedMarks = new ArrayList<>();
        final Map<Long, Vacancy> byId = new HashMap<>();

        FakeVacancyRepo() { super(null); }

        void put(Vacancy v) { byId.put(v.getId(), v); }

        @Override public void markModerationQueued(List<Long> ids) {
            queuedCalls.addAll(ids);
            for (Long id : ids) setStatus(id, "queued");
        }
        @Override public List<Vacancy> findAllSentForModeration() {
            return byId.values().stream().filter(v -> "sent".equals(v.getModerationStatus()))
                .sorted(Comparator.comparing(Vacancy::getId)).toList();
        }
        @Override public List<Vacancy> findNextQueuedBatchForModeration(int limit) {
            return byId.values().stream().filter(v -> "queued".equals(v.getModerationStatus()))
                .sorted(Comparator.comparing(Vacancy::getId)).limit(limit).toList();
        }
        @Override public void markModerationSent(List<Long> ids) {
            sentMarks.addAll(ids);
            for (Long id : ids) setStatus(id, "sent");
        }
        @Override public void markModerationApproved(Long id) {
            approvedMarks.add(id);
            setStatus(id, "approved");
        }
        @Override public void markModerationRejected(Long id) {
            rejectedMarks.add(id);
            setStatus(id, "rejected");
        }
        @Override public Optional<Vacancy> findById(Long id) { return Optional.ofNullable(byId.get(id)); }

        private void setStatus(Long id, String status) {
            Vacancy v = byId.get(id);
            if (v != null) v.setModerationStatus(status);
        }
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
        final List<List<Long>> sentBatches = new ArrayList<>();
        final List<Long> deletedMessageIds = new ArrayList<>();
        boolean sendResult = true;
        @Override
        public boolean sendModerationCardBatch(String message, List<Long> vacancyIds) {
            sentCards.add(message);
            sentBatches.add(vacancyIds);
            return sendResult;
        }
        @Override
        public void deleteModerationCard(long messageId) {
            deletedMessageIds.add(messageId);
        }
    }

    private FakeVacancyRepo repo = new FakeVacancyRepo();
    private FakeProfileFactory profileFactory = new FakeProfileFactory();
    private RecordingChannelPublisher publisher = new RecordingChannelPublisher();
    private RecordingNotifier notifier = new RecordingNotifier();
    private ModerationService service = new ModerationService(repo, profileFactory, publisher, notifier);

    /** Realistic precondition: advanceQueue put it here right before the card went
     *  out, and that's the only state resolveApprove/resolveReject are meant to act
     *  on (see alreadySent) — tests that specifically exercise the replay guard or
     *  the queue itself override the status explicitly. */
    private Vacancy vacancy(long id, Long searchId, String title) {
        Vacancy v = new Vacancy();
        v.setId(id);
        v.setHhId(String.valueOf(id));
        v.setSearchId(searchId);
        v.setTitle(title);
        v.setUrl("https://hh.ru/vacancy/" + id);
        v.setModerationStatus("sent");
        return v;
    }

    private Vacancy queuedVacancy(long id, Long searchId, String title) {
        Vacancy v = vacancy(id, searchId, title);
        v.setModerationStatus("queued");
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
        repo.put(vacancy(1, 10L, "В процессе")); // status 'sent' by default
        repo.put(queuedVacancy(2, 10L, "Ждёт"));

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
        repo.put(queuedVacancy(5, 10L, "Оператор поддержки"));

        service.advanceQueue();

        assertEquals(1, notifier.sentCards.size());
        assertTrue(notifier.sentCards.get(0).contains("Оператор поддержки"));
        assertEquals(List.of(5L), notifier.sentBatches.get(0));
        assertEquals(List.of(5L), repo.sentMarks);
    }

    @Test
    void advanceQueue_severalQueued_sendsThemAllAsOneBatchUpToLimit() {
        for (long id = 1; id <= 7; id++) {
            repo.put(queuedVacancy(id, 10L, "Вакансия " + id));
        }

        service.advanceQueue();

        assertEquals(1, notifier.sentCards.size(), "весь батч — одно сообщение, не N отдельных");
        assertEquals(ModerationService.MODERATION_BATCH_SIZE, notifier.sentBatches.get(0).size(),
            "батч ограничен MODERATION_BATCH_SIZE, оставшееся ждёт следующего тика");
        assertEquals(ModerationService.MODERATION_BATCH_SIZE, repo.sentMarks.size());
    }

    @Test
    void advanceQueue_sendFails_doesNotMarkSent() {
        notifier.sendResult = false;
        repo.put(queuedVacancy(5, 10L, "Оператор поддержки"));

        service.advanceQueue();

        assertTrue(repo.sentMarks.isEmpty(), "неудачная отправка не должна считаться показанной карточкой");
    }

    @Test
    void resolveApprove_publishesViaChannelPublisherWithReconstructedJob() {
        Vacancy v = vacancy(7, 10L, "Аналитик");
        repo.put(v);
        SearchJob reconstructed = job("Без техстека", 10L);
        profileFactory.jobs.put(10L, reconstructed);

        service.resolveApprove(7L, 555L);

        assertEquals(List.of(7L), repo.approvedMarks);
        assertEquals(List.of(v), publisher.sentVacancies);
        assertSame(reconstructed, publisher.sentJob);
        assertTrue(repo.rejectedMarks.isEmpty());
        assertEquals(List.of(555L), notifier.deletedMessageIds,
            "карточка должна удаляться из чата после решения, а не висеть с кликабельными кнопками");
    }

    @Test
    void resolveApprove_nullMessageId_doesNotAttemptDelete() {
        repo.put(vacancy(7, 10L, "Аналитик"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L, null);

        assertTrue(notifier.deletedMessageIds.isEmpty());
    }

    @Test
    void resolveReject_deletesTheCard() {
        service.resolveReject(4L, 777L);

        assertEquals(List.of(777L), notifier.deletedMessageIds);
    }

    @Test
    void resolveApprove_vacancyNotFound_doesNotThrowOrPublish() {
        assertDoesNotThrow(() -> service.resolveApprove(999L, 555L));
        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveApprove_searchNoLongerExists_rejectsInsteadOfPublishing() {
        // Search deleted/disabled since this vacancy was queued — nothing sane to publish
        // to, same fail-open philosophy as ChannelPublisher's own missing-chatId guards.
        repo.put(vacancy(8, 999L, "Пропавший поиск"));

        service.resolveApprove(8L, 555L);

        assertEquals(List.of(8L), repo.rejectedMarks);
        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveApprove_advancesQueueAfterward() {
        repo.put(vacancy(7, 10L, "Аналитик"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));
        repo.put(queuedVacancy(9, 10L, "Следующая в очереди"));

        service.resolveApprove(7L, 555L);

        assertEquals(1, notifier.sentCards.size(), "после решения должна показаться следующая карточка");
        assertTrue(notifier.sentCards.get(0).contains("Следующая в очереди"));
    }

    @Test
    void resolveReject_marksRejectedAndAdvancesQueue() {
        repo.put(queuedVacancy(9, 10L, "Следующая в очереди"));

        service.resolveReject(4L, 555L);

        assertEquals(List.of(4L), repo.rejectedMarks);
        assertTrue(publisher.sentVacancies.isEmpty());
        assertEquals(1, notifier.sentCards.size());
    }

    // ── Батч: карточка остаётся, пока не решены ВСЕ вакансии в ней ──

    @Test
    void resolveApprove_otherVacancyStillPendingInBatch_cardStaysNoAdvance() {
        repo.put(vacancy(1, 10L, "Первая"));
        repo.put(vacancy(2, 10L, "Вторая")); // тоже 'sent' — тот же батч
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(1L, 555L);

        assertEquals(List.of(1L), repo.approvedMarks, "решена только первая");
        assertTrue(notifier.deletedMessageIds.isEmpty(), "карточка не должна удаляться — вторая вакансия ещё не решена");
        assertTrue(notifier.sentCards.isEmpty(), "следующий батч не должен начинаться, пока текущий не закрыт");
    }

    @Test
    void resolveApprove_lastVacancyInBatch_deletesCardAndAdvances() {
        Vacancy first = vacancy(1, 10L, "Первая");
        first.setModerationStatus("approved"); // первая уже решена ранее
        repo.put(first);
        repo.put(vacancy(2, 10L, "Вторая")); // последняя ещё 'sent'
        profileFactory.jobs.put(10L, job("Без техстека", 10L));
        repo.put(queuedVacancy(3, 10L, "Следующий батч"));

        service.resolveApprove(2L, 555L);

        assertEquals(List.of(2L), repo.approvedMarks);
        assertEquals(List.of(555L), notifier.deletedMessageIds, "последняя вакансия в батче решена — карточку убираем");
        assertEquals(1, notifier.sentCards.size(), "и сразу показываем следующий батч");
    }

    @Test
    void resolveReject_otherVacancyStillPendingInBatch_cardStays() {
        repo.put(vacancy(1, 10L, "Первая"));
        repo.put(vacancy(2, 10L, "Вторая"));

        service.resolveReject(1L, 555L);

        assertEquals(List.of(1L), repo.rejectedMarks);
        assertTrue(notifier.deletedMessageIds.isEmpty());
    }

    // ── "Одобрить всё" ──

    @Test
    void resolveApproveAll_publishesEveryPendingVacancyInBatch() {
        repo.put(vacancy(1, 10L, "Первая"));
        repo.put(vacancy(2, 10L, "Вторая"));
        repo.put(vacancy(3, 10L, "Третья"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApproveAll(555L);

        assertEquals(List.of(1L, 2L, 3L), repo.approvedMarks.stream().sorted().toList());
        assertEquals(3, publisher.sentVacancies.size());
        assertEquals(List.of(555L), notifier.deletedMessageIds, "карточка удаляется один раз, не по разу на вакансию");
    }

    @Test
    void resolveApproveAll_advancesQueueAfterward() {
        repo.put(vacancy(1, 10L, "Первая"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));
        repo.put(queuedVacancy(2, 10L, "Следующий батч"));

        service.resolveApproveAll(555L);

        assertEquals(1, notifier.sentCards.size());
    }

    @Test
    void resolveApproveAll_mixedSearches_reconstructsJobPerVacancy() {
        repo.put(vacancy(1, 10L, "Из поиска A"));
        repo.put(vacancy(2, 20L, "Из поиска B"));
        profileFactory.jobs.put(10L, job("Поиск A", 10L));
        profileFactory.jobs.put(20L, job("Поиск B", 20L));

        service.resolveApproveAll(555L);

        assertEquals(2, publisher.sentVacancies.size());
    }

    @Test
    void resolveApproveAll_oneSearchGone_rejectsThatOneButPublishesRest() {
        repo.put(vacancy(1, 10L, "Живой поиск"));
        repo.put(vacancy(2, 999L, "Пропавший поиск"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApproveAll(555L);

        assertEquals(1, publisher.sentVacancies.size());
        assertEquals(List.of(2L), repo.rejectedMarks);
        assertEquals(List.of(1L), repo.approvedMarks);
    }

    @Test
    void resolveApproveAll_nothingPending_justDeletesCard() {
        service.resolveApproveAll(555L);

        assertEquals(List.of(555L), notifier.deletedMessageIds);
        assertTrue(publisher.sentVacancies.isEmpty());
    }

    // ── Replay guard: a Telegram callback_query is redelivered until the offset
    //    advances past it, and the poller's offset resets to 0 on every app restart
    //    unless persisted — see ModerationBotPoller. A stale/duplicate tap must never
    //    re-publish or re-resolve something already decided. ──

    @Test
    void resolveApprove_vacancyAlreadyApproved_doesNotPublishAgain() {
        Vacancy alreadyApproved = vacancy(7, 10L, "Уже одобрена");
        alreadyApproved.setModerationStatus("approved"); // not 'sent' — replay of an old tap
        repo.put(alreadyApproved);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L, 555L);

        assertTrue(publisher.sentVacancies.isEmpty(), "повторный approve не должен публиковать вакансию второй раз");
        assertTrue(repo.approvedMarks.isEmpty(), "moderation_status уже 'approved' — повторно помечать не нужно");
    }

    @Test
    void resolveApprove_vacancyAlreadyRejected_doesNotPublish() {
        Vacancy alreadyRejected = vacancy(7, 10L, "Уже отклонена");
        alreadyRejected.setModerationStatus("rejected");
        repo.put(alreadyRejected);
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L, 555L);

        assertTrue(publisher.sentVacancies.isEmpty(),
            "просроченный approve на уже отклонённую вакансию не должен всё равно публиковать её");
    }

    @Test
    void resolveApprove_vacancyStillQueuedNotYetSent_doesNotSkipAhead() {
        // Defensive edge case: a tap can't legitimately arrive for a card that hasn't
        // been sent yet, but if it somehow does (clock skew, manual DB edit), don't
        // publish something the owner never actually saw.
        repo.put(queuedVacancy(7, 10L, "Ещё не отправлена"));
        profileFactory.jobs.put(10L, job("Без техстека", 10L));

        service.resolveApprove(7L, 555L);

        assertTrue(publisher.sentVacancies.isEmpty());
    }

    @Test
    void resolveReject_vacancyAlreadyApproved_doesNotOverwriteDecision() {
        Vacancy alreadyApproved = vacancy(7, 10L, "Уже одобрена");
        alreadyApproved.setModerationStatus("approved");
        repo.put(alreadyApproved);

        service.resolveReject(7L, 555L);

        assertTrue(repo.rejectedMarks.isEmpty(),
            "просроченный reject не должен задним числом отменять уже состоявшуюся публикацию");
    }

    @Test
    void resolveApprove_replayAfterBatchAlreadyFullyResolved_doesNotWronglyDeleteLiveCard() {
        // Регрессия батчинга: реплей на УЖЕ решённую вакансию из батча не должен
        // задевать карточку СЛЕДУЮЩЕГО батча, который к этому моменту уже могли отправить.
        Vacancy stale = vacancy(1, 10L, "Из старого батча");
        stale.setModerationStatus("approved"); // уже решена в прошлом
        repo.put(stale);
        repo.put(vacancy(2, 10L, "Из НОВОГО батча")); // 'sent' — текущий, живой батч

        service.resolveApprove(1L, 555L); // устаревший replay на вакансию 1

        assertTrue(notifier.deletedMessageIds.isEmpty(),
            "батч ещё не пуст (вакансия 2 всё ещё 'sent') — карточку удалять нельзя");
    }
}
