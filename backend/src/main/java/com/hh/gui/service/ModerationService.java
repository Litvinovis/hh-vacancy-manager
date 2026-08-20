package com.hh.gui.service;

import com.hh.gui.config.RuntimeConfig;
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
 * Manual gate between AI approval and the public channel: EDITORIAL vacancies that
 * passed everything else wait here — grouped into ONE Telegram card of up to
 * {@link #MODERATION_BATCH_SIZE} vacancies at a time, sent to the owner's personal
 * chat, each with its own ✅/❌ row plus (batches bigger than one) an "approve all"
 * row — until a human resolves every vacancy in the batch, one way or another. No
 * timeout; a card just waits (see ModerationBotPoller for the callback_query side).
 *
 * One BATCH in flight at a time by design (advanceQueue only sends the next batch
 * once every vacancy in the current one is resolved) — a wall of unread cards
 * defeats the point of reviewing each one, which is exactly what the original
 * "по одной" request meant; grouping several vacancies into one message is a
 * presentation change (less scrolling), not a return to publishing without review —
 * every vacancy still gets its own explicit tap, "approve all" included, since the
 * owner still has to look at the batch and choose to tap it.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    // Matches ChannelPublisher.PUBLISH_BATCH_SIZE — no reason for moderation cards to
    // group differently than the batches they'll eventually turn into public posts.
    // Used for RuntimeConfig.moderationMode="batch"; "single" mode uses 1 instead —
    // see effectiveBatchSize().
    static final int MODERATION_BATCH_SIZE = 5;

    private final VacancyRepository vacancyRepo;
    private final SearchProfileFactory profileFactory;
    private final ChannelPublisher channelPublisher;
    private final TelegramNotifier telegramNotifier;
    private final RuntimeConfig runtimeConfig;

    public ModerationService(VacancyRepository vacancyRepo, SearchProfileFactory profileFactory,
                              ChannelPublisher channelPublisher, TelegramNotifier telegramNotifier,
                              RuntimeConfig runtimeConfig) {
        this.vacancyRepo = vacancyRepo;
        this.profileFactory = profileFactory;
        this.channelPublisher = channelPublisher;
        this.telegramNotifier = telegramNotifier;
        this.runtimeConfig = runtimeConfig;
    }

    /** "single" mode groups one vacancy per card (the original design); "batch" (and
     *  "auto", though auto never reaches here since VacancyPipelineService bypasses
     *  moderation entirely in that mode) group up to MODERATION_BATCH_SIZE. */
    private int effectiveBatchSize() {
        return "single".equals(runtimeConfig.getModerationMode()) ? 1 : MODERATION_BATCH_SIZE;
    }

    /** Called from sendReport instead of channelPublisher.send, for EDITORIAL jobs while
     *  moderation is enabled — see VacancyPipelineService. Doesn't send anything itself;
     *  advanceQueue (the scheduler tick) picks these up in order. */
    public void queueForModeration(List<Vacancy> approved, SearchJob job) {
        if (approved.isEmpty()) return;
        vacancyRepo.markModerationQueued(approved.stream().map(Vacancy::getId).toList());
        log.info("На модерацию поставлено {} вакансий ({} · {})", approved.size(), job.personName, job.searchName);
    }

    /** Sends the next batch card, but only if nothing is currently awaiting a reply —
     *  called both on a timer (PipelineScheduler) and right after every vacancy in the
     *  current batch has been resolved, so the next batch shows up immediately rather
     *  than waiting for the next tick. */
    public void advanceQueue() {
        if (!vacancyRepo.findAllSentForModeration().isEmpty()) return;
        List<Vacancy> batch = vacancyRepo.findNextQueuedBatchForModeration(effectiveBatchSize());
        if (batch.isEmpty()) return;
        String card = formatBatchCard(batch);
        List<Long> ids = batch.stream().map(Vacancy::getId).toList();
        if (telegramNotifier.sendModerationCardBatch(card, ids)) {
            vacancyRepo.markModerationSent(ids);
        } else {
            log.warn("Не удалось отправить карточку модерации (батч из {}) — попробуем на следующем тике", batch.size());
        }
    }

    private String formatBatchCard(List<Vacancy> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 <b>На модерацию");
        if (batch.size() > 1) sb.append(" (").append(batch.size()).append(")");
        sb.append("</b>\n\n");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append("➖➖➖➖➖\n\n");
            if (batch.size() > 1) sb.append(i + 1).append(". ");
            sb.append(VacancyPostFormatter.publicPost(batch.get(i)));
        }
        return sb.toString();
    }

    /** ✅ tapped for ONE vacancy in the batch: publishes exactly like the automatic path
     *  would have (same ChannelPublisher.send, immediate-or-paced per the search's own
     *  publishPaceMinutes). The card stays up — other vacancies in the same batch may
     *  still be unresolved — until {@link #finishItemInBatch} finds the batch empty.
     *  @param messageId the tapped card's own message id, so it can be deleted once the
     *                    WHOLE batch is resolved (see TelegramNotifier.deleteModerationCard)
     *                    instead of leaving its buttons sitting there clickable forever —
     *                    null on the rare update shape that doesn't carry one. */
    public void resolveApprove(Long vacancyId, Long messageId) {
        Optional<Vacancy> vacancyOpt = vacancyRepo.findById(vacancyId);
        if (vacancyOpt.isEmpty()) {
            log.warn("Модерация: вакансия id={} не найдена (approve)", vacancyId);
            finishItemInBatch(messageId);
            return;
        }
        Vacancy v = vacancyOpt.get();
        if (!alreadySent(v, vacancyId, "approve")) {
            finishItemInBatch(messageId);
            return;
        }
        approveOne(v);
        finishItemInBatch(messageId);
    }

    /** ❌ tapped for ONE vacancy in the batch: resolved as never-publish, same as a
     *  similarity-dedup drop. @param messageId see resolveApprove's javadoc. */
    public void resolveReject(Long vacancyId, Long messageId) {
        Optional<Vacancy> vacancyOpt = vacancyRepo.findById(vacancyId);
        if (vacancyOpt.isPresent() && !alreadySent(vacancyOpt.get(), vacancyId, "reject")) {
            finishItemInBatch(messageId);
            return;
        }
        vacancyRepo.markModerationRejected(vacancyId);
        finishItemInBatch(messageId);
    }

    /**
     * "✅ Одобрить всё" tapped: resolves EVERY vacancy still 'sent' in the current
     * batch the same way a single ✅ tap would, then cleans up once for the whole
     * batch. Faster than tapping each one, but still one explicit tap over the whole
     * visible batch — not a bypass of review, just of per-item clicking (see class
     * javadoc on why that distinction matters here).
     */
    public void resolveApproveAll(Long messageId) {
        List<Vacancy> batch = vacancyRepo.findAllSentForModeration();
        if (batch.isEmpty()) {
            deleteCard(messageId);
            return;
        }
        log.info("Модерация: «Одобрить всё» — {} вакансий", batch.size());
        for (Vacancy v : batch) {
            approveOne(v);
        }
        deleteCard(messageId);
        advanceQueue();
    }

    /** Shared by resolveApprove and resolveApproveAll: publish this one vacancy via the
     *  same path the automatic (unmoderated) flow uses, or reject it if its search was
     *  deleted/disabled since it was queued — nothing sane to publish to. */
    private void approveOne(Vacancy v) {
        Optional<SearchJob> jobOpt = v.getSearchId() != null ? profileFactory.buildForSearchId(v.getSearchId()) : Optional.empty();
        if (jobOpt.isEmpty()) {
            log.warn("Модерация: не удалось восстановить поиск для вакансии id={} (search_id={}) — публикация отменена",
                v.getId(), v.getSearchId());
            vacancyRepo.markModerationRejected(v.getId());
            return;
        }
        vacancyRepo.markModerationApproved(v.getId());
        channelPublisher.send(List.of(v), jobOpt.get());
    }

    /** After resolving one item, cleans up the card and starts the next batch ONLY once
     *  nothing in the current batch is still 'sent' — other vacancies in the same
     *  message may still be waiting for their own tap. */
    private void finishItemInBatch(Long messageId) {
        if (vacancyRepo.findAllSentForModeration().isEmpty()) {
            deleteCard(messageId);
            advanceQueue();
        }
    }

    private void deleteCard(Long messageId) {
        if (messageId != null) telegramNotifier.deleteModerationCard(messageId);
    }

    /**
     * True only when the vacancy is still waiting on a decision ('sent' — the state
     * advanceQueue put it in right before the batch went out). ModerationBotPoller now
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
