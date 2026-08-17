package com.hh.gui.service;

import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.util.VacancyPostFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Manual gate between AI approval and the public channel: an EDITORIAL vacancy that
 * passed everything else waits here — one Telegram card per vacancy, sent to the
 * owner's personal chat with ✅/❌ buttons — until a human taps a decision. No timeout;
 * a card just waits (see ModerationBotPoller for the callback_query side).
 *
 * One card in flight at a time by design (advanceQueue only sends the next once the
 * current one is resolved) — a wall of unread cards defeats the point of reviewing
 * each one, which is exactly what "по одной" in the request meant.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final VacancyRepository vacancyRepo;
    private final SearchProfileFactory profileFactory;
    private final ChannelPublisher channelPublisher;
    private final TelegramNotifier telegramNotifier;

    public ModerationService(VacancyRepository vacancyRepo, SearchProfileFactory profileFactory,
                              ChannelPublisher channelPublisher, TelegramNotifier telegramNotifier) {
        this.vacancyRepo = vacancyRepo;
        this.profileFactory = profileFactory;
        this.channelPublisher = channelPublisher;
        this.telegramNotifier = telegramNotifier;
    }

    /** Called from sendReport instead of channelPublisher.send, for EDITORIAL jobs while
     *  moderation is enabled — see VacancyPipelineService. Doesn't send anything itself;
     *  advanceQueue (the scheduler tick) picks these up in order. */
    public void queueForModeration(List<Vacancy> approved, SearchJob job) {
        if (approved.isEmpty()) return;
        vacancyRepo.markModerationQueued(approved.stream().map(Vacancy::getId).toList());
        log.info("На модерацию поставлено {} вакансий ({} · {})", approved.size(), job.personName, job.searchName);
    }

    /** Sends the next queued card, but only if nothing is currently awaiting a reply —
     *  called both on a timer (PipelineScheduler) and right after a decision resolves,
     *  so the next card shows up immediately rather than waiting for the next tick. */
    public void advanceQueue() {
        if (vacancyRepo.findCurrentSentForModeration().isPresent()) return;
        Optional<Vacancy> next = vacancyRepo.findNextQueuedForModeration();
        if (next.isEmpty()) return;
        Vacancy v = next.get();
        String card = "🔔 <b>На модерацию</b>\n\n" + VacancyPostFormatter.publicPost(v);
        if (telegramNotifier.sendModerationCard(card, v.getId())) {
            vacancyRepo.markModerationSent(v.getId());
        } else {
            log.warn("Не удалось отправить карточку модерации id={} — попробуем на следующем тике", v.getId());
        }
    }

    /** ✅ tapped: publishes exactly like the automatic path would have (same
     *  ChannelPublisher.send, immediate-or-paced per the search's own publishPaceMinutes),
     *  just for this one vacancy instead of a whole approved batch.
     *  @param messageId the tapped card's own message id, so it can be deleted afterward
     *                    (see TelegramNotifier.deleteModerationCard) instead of leaving
     *                    its ✅/❌ buttons sitting there clickable forever — null on the
     *                    rare update shape that doesn't carry one, in which case there's
     *                    just nothing to clean up. */
    public void resolveApprove(Long vacancyId, Long messageId) {
        Optional<Vacancy> vacancyOpt = vacancyRepo.findById(vacancyId);
        if (vacancyOpt.isEmpty()) {
            log.warn("Модерация: вакансия id={} не найдена (approve)", vacancyId);
            deleteCard(messageId);
            advanceQueue();
            return;
        }
        Vacancy v = vacancyOpt.get();
        if (!alreadySent(v, vacancyId, "approve")) {
            deleteCard(messageId);
            advanceQueue(); // no-op unless the queue is somehow stuck with nothing 'sent'
            return;
        }
        Optional<SearchJob> jobOpt = v.getSearchId() != null ? profileFactory.buildForSearchId(v.getSearchId()) : Optional.empty();
        if (jobOpt.isEmpty()) {
            log.warn("Модерация: не удалось восстановить поиск для вакансии id={} (search_id={}) — публикация отменена",
                vacancyId, v.getSearchId());
            vacancyRepo.markModerationRejected(vacancyId);
            deleteCard(messageId);
            advanceQueue();
            return;
        }
        vacancyRepo.markModerationApproved(vacancyId);
        channelPublisher.send(List.of(v), jobOpt.get());
        deleteCard(messageId);
        advanceQueue();
    }

    /** ❌ tapped: resolved as never-publish, same as a similarity-dedup drop.
     *  @param messageId see resolveApprove's javadoc. */
    public void resolveReject(Long vacancyId, Long messageId) {
        Optional<Vacancy> vacancyOpt = vacancyRepo.findById(vacancyId);
        if (vacancyOpt.isPresent() && !alreadySent(vacancyOpt.get(), vacancyId, "reject")) {
            deleteCard(messageId);
            advanceQueue();
            return;
        }
        vacancyRepo.markModerationRejected(vacancyId);
        deleteCard(messageId);
        advanceQueue();
    }

    private void deleteCard(Long messageId) {
        if (messageId != null) telegramNotifier.deleteModerationCard(messageId);
    }

    /**
     * True only when the vacancy is still waiting on a decision ('sent' — the state
     * advanceQueue put it in right before the card went out). ModerationBotPoller now
     * persists its offset (see its javadoc), so a genuine restart-triggered replay of an
     * already-resolved callback_query shouldn't recur — but this guard stays as a second,
     * independent safety net regardless of the reason a stale/duplicate tap might arrive.
     * Live-observed before the offset fix: dozens of vacancies auto-published across
     * several restarts with no one touching a button, all traced back to that. Guarding
     * on the expected precondition state makes every handler here safe to call twice (or
     * a hundred times) for the same vacancy — a replay just finds it no longer 'sent' and
     * is a no-op, logged so a real bug elsewhere doesn't look like ordinary drift.
     */
    private boolean alreadySent(Vacancy v, Long vacancyId, String action) {
        if ("sent".equals(v.getModerationStatus())) return true;
        log.info("Модерация: повтор/устаревший callback ({}) для id={}, но статус уже '{}' — пропускаем (вероятно, replay после рестарта)",
            action, vacancyId, v.getModerationStatus());
        return false;
    }
}
