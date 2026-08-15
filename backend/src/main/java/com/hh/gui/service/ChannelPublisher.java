package com.hh.gui.service;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.util.TelegramPostParser;
import com.hh.gui.util.VacancyPostFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Everything between "these vacancies were approved" and "they appeared in a
 * Telegram channel": immediate sends, the paced publish queue, the overnight
 * window, and the delayed-publish channel.
 *
 * Split out of VacancyPipelineService, which had grown to hold twelve unrelated
 * responsibility clusters behind a 12-argument constructor. This one needs five
 * collaborators and answers one question, so its behaviour can be reasoned about
 * — and tested — without standing up discovery, scraping or AI analysis.
 *
 * Publishing is deliberately not transactional: sending to Telegram is an external
 * side effect that can't be rolled back, so every path here marks rows as sent only
 * after the send actually succeeded, and leaves them for the next tick otherwise.
 */
@Service
public class ChannelPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChannelPublisher.class);

    // Publish-queue pacing (enqueue / publishDueQueued): posts go out in batches
    // instead of one at a time, and the gap between batches breathes with how deep the
    // queue is — a big backlog drains faster, a shallow one is spaced out more —
    // instead of a single fixed publishPaceMinutes regardless of how much is waiting.
    static final int PUBLISH_BATCH_SIZE = 5;
    // Calibration point: at REFERENCE_QUEUE_BATCHES batches queued, the pace is exactly
    // the search's own publishPaceMinutes; fewer batches waiting eases off toward
    // MAX_PACE_MINUTES, more batches tightens toward MIN_PACE_MINUTES.
    private static final int REFERENCE_QUEUE_BATCHES = 5;
    private static final long MIN_PACE_MINUTES = 3;
    private static final long MAX_PACE_MINUTES = 60;
    // Public channel posts only go out 07:00–23:00 local (server timezone) — anything
    // that would land overnight is pushed to 07:00 the next morning instead, so the
    // queue quietly accumulates overnight rather than posting into an empty-audience window.
    private static final int PUBLISH_WINDOW_START_HOUR = 7;
    private static final int PUBLISH_WINDOW_END_HOUR = 23;

    private final VacancyRepository vacancyRepo;
    private final SearchRepository searchRepo;
    private final TelegramNotifier telegramNotifier;
    private final TelegramMetrics telegramMetrics;
    private final RuntimeConfig runtimeConfig;

    public ChannelPublisher(VacancyRepository vacancyRepo, SearchRepository searchRepo,
                            TelegramNotifier telegramNotifier, TelegramMetrics telegramMetrics,
                            RuntimeConfig runtimeConfig) {
        this.vacancyRepo = vacancyRepo;
        this.searchRepo = searchRepo;
        this.telegramNotifier = telegramNotifier;
        this.telegramMetrics = telegramMetrics;
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * Publishes an approved batch to the search's own channel, one post per vacancy
     * (a channel feed reads better as a stream of single postings than a batch, and
     * each one stands alone without a "🔍 person · search" header that only makes
     * sense for a personal report) using the public template.
     *
     * With publishPaceMinutes set, a whole approved batch (e.g. 10 vacancies from one
     * pipeline run) would otherwise land in the channel as a burst within seconds —
     * {@link #enqueue} staggers them instead; without it, sends immediately as before.
     */
    void send(List<Vacancy> approved, SearchJob job) {
        if (job.publishPaceMinutes != null && job.publishPaceMinutes > 0) {
            enqueue(approved, job);
            return;
        }
        int notifiedCount = 0;
        for (Vacancy v : approved) {
            if (telegramNotifier.sendViaChannelBot(VacancyPostFormatter.publicPost(v), job.chatId)) {
                vacancyRepo.markNotified(List.of(v.getId()));
                notifiedCount++;
            } else {
                log.warn("Не удалось опубликовать вакансию id={} ({} · {}) — останется неуведомлённой",
                    v.getId(), job.personName, job.searchName);
            }
        }
        log.info("Публичные посты отправлены ({} · {}, {}/{})",
            job.personName, job.searchName, notifiedCount, approved.size());
    }

    /**
     * Stamps a queued_publish_at on each vacancy, chained after whatever's already
     * queued for this search (findQueueTailTime) so a second approved batch arriving
     * before the first has drained doesn't overlap it — just extends the line. Vacancies
     * are grouped into PUBLISH_BATCH_SIZE-sized batches sharing one due time; each next
     * batch's time is the previous one plus a pace that itself depends on how deep the
     * queue already is (see {@link #dynamicPaceMinutes}) — and any time landing outside
     * the 07:00–23:00 publish window gets pushed to the next morning (see
     * {@link #pushPastNightWindow}).
     */
    void enqueue(List<Vacancy> approved, SearchJob job) {
        Instant cursor = vacancyRepo.findQueueTailTime(job.searchId)
            .map(Instant::parse)
            .filter(t -> t.isAfter(Instant.now()))
            .orElse(Instant.now());
        cursor = pushPastNightWindow(cursor);

        int alreadyQueued = vacancyRepo.countQueued(job.searchId);
        List<Long> ids = new ArrayList<>();
        List<String> publishAts = new ArrayList<>();
        for (int i = 0; i < approved.size(); i++) {
            // Bug fixed live 2026-08-15: this used to gate on the LOCAL loop index i,
            // so every call that brought fewer than PUBLISH_BATCH_SIZE new items (the
            // overwhelmingly common case — approvals trickle in a handful at a time,
            // not in one big burst) never advanced at all and just inherited whatever
            // cursor findQueueTailTime() returned. With a standing backlog (queue is
            // essentially never empty), that meant every small call silently piled its
            // items onto the SAME due time as whatever was already queued there —
            // observed live as groups of 10 (two 5-item calls landing on one slot)
            // instead of the intended groups of 5, and the "batch of 5" behavior the
            // pace/window design depends on effectively never happened. Gating on the
            // GLOBAL queue position (already-queued count + this call's own index)
            // instead correctly resumes filling a partially-full batch left by an
            // earlier call before advancing to a fresh one.
            int queuePosition = alreadyQueued + i;
            if (queuePosition > 0 && queuePosition % PUBLISH_BATCH_SIZE == 0) {
                int batchesQueued = queuePosition / PUBLISH_BATCH_SIZE;
                long paceMinutes = dynamicPaceMinutes(job.publishPaceMinutes, batchesQueued);
                cursor = pushPastNightWindow(cursor.plusSeconds(paceMinutes * 60L));
            }
            ids.add(approved.get(i).getId());
            publishAts.add(cursor.toString());
        }
        vacancyRepo.enqueuePublish(ids, publishAts);
        log.info("В очередь публикации поставлено {} вакансий батчами по {} ({} · {})",
            approved.size(), PUBLISH_BATCH_SIZE, job.personName, job.searchName);
    }

    /**
     * The gap before the NEXT batch, in minutes — breathes with queuedBatches (how many
     * PUBLISH_BATCH_SIZE-sized batches are already waiting for this search): more queued
     * means less time between batches (drain faster), less queued means more time
     * (spread out, don't rush a trickle). basePaceMinutes (the search's own
     * publishPaceMinutes) is the pace exactly AT the REFERENCE_QUEUE_BATCHES calibration
     * point; the result is always clamped to [MIN_PACE_MINUTES, MAX_PACE_MINUTES]
     * regardless of how far the actual queue depth is from that point.
     */
    static long dynamicPaceMinutes(Integer basePaceMinutes, int queuedBatches) {
        long base = basePaceMinutes != null && basePaceMinutes > 0 ? basePaceMinutes : REFERENCE_QUEUE_BATCHES;
        if (queuedBatches <= 0) return Math.min(base, MAX_PACE_MINUTES);
        long dynamic = base * REFERENCE_QUEUE_BATCHES / queuedBatches;
        return Math.max(MIN_PACE_MINUTES, Math.min(MAX_PACE_MINUTES, dynamic));
    }

    static boolean isOutsidePublishWindow(Instant instant) {
        int hour = instant.atZone(ZoneId.systemDefault()).getHour();
        return hour >= PUBLISH_WINDOW_END_HOUR || hour < PUBLISH_WINDOW_START_HOUR;
    }

    /** Rolls a candidate publish time forward to PUBLISH_WINDOW_START_HOUR the same or
     *  next local day if it falls outside the 07:00–23:00 window — the queue accumulates
     *  overnight instead of posting into an empty-audience window. */
    static Instant pushPastNightWindow(Instant candidate) {
        if (!isOutsidePublishWindow(candidate)) return candidate;
        ZonedDateTime zdt = candidate.atZone(ZoneId.systemDefault());
        ZonedDateTime morning = zdt.withHour(PUBLISH_WINDOW_START_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (zdt.getHour() >= PUBLISH_WINDOW_END_HOUR) morning = morning.plusDays(1);
        return morning.toInstant();
    }

    /**
     * Fired on the queued-publish scheduler tick (see PipelineScheduler). Sends up to
     * PUBLISH_BATCH_SIZE due posts per search per tick as ONE combined message (see
     * {@link #enqueue} for why batches at all) — a backlog beyond that stays queued and
     * is picked up by the following ticks. Skips entirely outside the 07:00–23:00 window.
     */
    public void publishDueQueued(int limit) {
        if (!runtimeConfig.isChannelNotificationsEnabled()) return;
        if (isOutsidePublishWindow(Instant.now())) return;
        doPublishDueQueued(limit);
    }

    /** Split out from {@link #publishDueQueued} so tests can exercise the batching/sending
     *  logic without it being at the mercy of the real wall-clock publish window — a test
     *  run outside 07:00–23:00 local would otherwise silently no-op regardless of what
     *  it's actually asserting. */
    void doPublishDueQueued(int limit) {
        List<Vacancy> due = vacancyRepo.findDueQueuedPublications(Instant.now().toString(), limit);
        if (due.isEmpty()) return;

        Map<Long, List<Vacancy>> bySearchId = due.stream()
            .filter(v -> v.getSearchId() != null)
            .collect(Collectors.groupingBy(Vacancy::getSearchId));

        for (var entry : bySearchId.entrySet()) {
            Optional<SearchConfig> searchOpt = searchRepo.findById(entry.getKey());
            if (searchOpt.isEmpty() || searchOpt.get().getChatId() == null || searchOpt.get().getChatId().isBlank()) {
                // Search deleted or its chat_id was cleared since queuing — leave
                // notified=0 forever rather than guess a destination (fail-open, same
                // as publishDueDelayed).
                continue;
            }
            String chatId = searchOpt.get().getChatId();
            List<Vacancy> dueForSearch = entry.getValue();
            // One combined message per tick, not PUBLISH_BATCH_SIZE separate ones — the
            // whole point of batching was fewer, denser posts (several vacancies per post),
            // not the same number of posts sent closer together. All-or-nothing send: a
            // single Telegram message can't partially succeed, so a failure leaves the
            // whole batch unnotified for the next tick to retry, same fail-open style as
            // the per-vacancy loop this replaced.
            List<Vacancy> batch = dueForSearch.size() > PUBLISH_BATCH_SIZE
                ? dueForSearch.subList(0, PUBLISH_BATCH_SIZE) : dueForSearch;
            if (telegramNotifier.sendViaChannelBot(formatBatch(batch), chatId)) {
                vacancyRepo.markNotified(batch.stream().map(Vacancy::getId).toList());
                for (Vacancy v : batch) telegramMetrics.recordPublished(TelegramPostParser.channelFromHhId(v.getHhId()));
            } else {
                log.warn("Публикация из очереди не удалась для батча из {} вакансий (search_id={})", batch.size(), entry.getKey());
            }
            if (dueForSearch.size() > batch.size()) {
                log.info("Очередь публикации (search_id={}): просрочено {} постов, отправлено {} одним сообщением — остальные следующими тиками",
                    entry.getKey(), dueForSearch.size(), batch.size());
            }
        }
    }

    /**
     * Fired on the delayed-publish scheduler tick (see PipelineScheduler). Groups due
     * rows by search_id to look up each search's delayed_chat_id, sends each vacancy
     * as a public post, and marks it delayed_notified — independent of the primary
     * notified flag, which this never touches.
     */
    public void publishDueDelayed(int limit) {
        if (!runtimeConfig.isChannelNotificationsEnabled()) return;
        List<Vacancy> due = vacancyRepo.findDueDelayedPublications(Instant.now().toString(), limit);
        if (due.isEmpty()) return;

        Map<Long, List<Vacancy>> bySearchId = due.stream()
            .filter(v -> v.getSearchId() != null)
            .collect(Collectors.groupingBy(Vacancy::getSearchId));

        for (var entry : bySearchId.entrySet()) {
            Optional<SearchConfig> searchOpt = searchRepo.findById(entry.getKey());
            if (searchOpt.isEmpty() || searchOpt.get().getDelayedChatId() == null
                    || searchOpt.get().getDelayedChatId().isBlank()) {
                // Search deleted or its delayed_chat_id was cleared since scheduling —
                // nothing sane to do with these; leave delayed_notified=0 forever rather
                // than guess a destination (matches the fail-open style used elsewhere).
                continue;
            }
            String delayedChatId = searchOpt.get().getDelayedChatId();
            for (Vacancy v : entry.getValue()) {
                if (telegramNotifier.sendViaChannelBot(VacancyPostFormatter.publicPost(v), delayedChatId)) {
                    vacancyRepo.markDelayedNotified(List.of(v.getId()));
                } else {
                    log.warn("Отложенная публикация не удалась для id={} (search_id={})", v.getId(), entry.getKey());
                }
            }
        }
    }

    /** Combines up to PUBLISH_BATCH_SIZE vacancies into ONE Telegram message — a divider
     *  between entries, each formatted exactly like a single-vacancy public post. Not
     *  chunked against Telegram's message limit: at PUBLISH_BATCH_SIZE=5 entries, even
     *  five maximally-truncated ones (title 150 + reason 300 + the rest) land comfortably
     *  under 4096 chars — the personal report's chunking exists for its unbounded batch
     *  sizes, which this deliberately small one never has. */
    String formatBatch(List<Vacancy> vacancies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vacancies.size(); i++) {
            if (i > 0) sb.append("➖➖➖➖➖\n\n");
            sb.append(VacancyPostFormatter.publicPost(vacancies.get(i)));
        }
        return sb.toString();
    }
}
