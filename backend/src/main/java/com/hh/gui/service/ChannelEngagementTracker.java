package com.hh.gui.service;

import com.hh.gui.client.TelegramClient;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "how is our channel doing" — subscriber counts, and the views and
 * reactions its posts collect. Everything here is observation only: it reads,
 * records gauges, and never changes what gets published.
 *
 * Split out of VacancyPipelineService as the second of the audit's three seams.
 * Engagement tracking had nothing in common with discovery or publishing beyond
 * sharing that class, and it runs on its own slow cadence (every 6 hours) rather
 * than with the pipeline.
 *
 * Every read fails soft: a channel that can't be polled is skipped, never
 * escalated, since one unreachable channel must not abort the sweep over the rest.
 */
@Service
public class ChannelEngagementTracker {

    private static final Logger log = LoggerFactory.getLogger(ChannelEngagementTracker.class);

    /** How many recent posts to read when sampling a channel's engagement. */
    private static final int ENGAGEMENT_SAMPLE_POSTS = 100;

    private final SearchRepository searchRepo;
    private final TelegramClient telegramClient;
    private final TelegramNotifier telegramNotifier;
    private final TelegramMetrics telegramMetrics;

    public ChannelEngagementTracker(SearchRepository searchRepo, TelegramClient telegramClient,
                                    TelegramNotifier telegramNotifier, TelegramMetrics telegramMetrics) {
        this.searchRepo = searchRepo;
        this.telegramClient = telegramClient;
        this.telegramNotifier = telegramNotifier;
        this.telegramMetrics = telegramMetrics;
    }

    /**
     * Polls the subscriber count of every distinct chat_id currently configured as a
     * search's public-post destination (see PipelineScheduler's SUBSCRIBER_COUNT_CHECK_INTERVAL)
     * and records it as a gauge — Grafana computes day/week deltas from the retained
     * time series via delta(), so nothing is persisted on this side. Distinct chat_ids
     * only: several searches can publish to the same channel, and polling it once per
     * search would just be redundant Bot API calls for an identical answer.
     *
     * Tagged by resolved @username, same as {@link #checkOwnChannels}'s views/reactions
     * gauges — falls back to the raw chat_id only when the channel has no public
     * username, so a reader can actually tell which channel a series is without cross
     * referencing numeric chat_ids.
     */
    public void checkSubscribers() {
        for (String chatId : distinctPublishChatIds()) {
            Integer count = telegramNotifier.getChatMemberCount(chatId);
            if (count != null) {
                String username = telegramNotifier.getChatUsername(chatId);
                telegramMetrics.recordSubscribers(username != null ? username : chatId, count);
            }
        }
    }

    /**
     * Reads recent posts from the app's OWN output channel(s) — the ones searches
     * actually publish to — via the same tg-scraper sidecar used for source channels,
     * and records their views/reactions. Distinct from the per-SOURCE-channel snapshot
     * discovery takes (see {@link #recordFrom}'s other call site): that one tracks
     * engagement on the channels vacancies are FOUND on, this one tracks engagement on
     * the channel this app actually PUBLISHES to — the SMM question a reader of the
     * dashboard actually cares about. The channel tag (its own username) is what tells
     * the two apart in Grafana; no separate metric name needed.
     *
     * tg-scraper's /channel endpoint takes a username, not a numeric chat_id, so this
     * resolves one via Bot API getChat first — a channel with no public username (fully
     * private) can't be read this way and is silently skipped, same fail-open style as
     * {@link #checkSubscribers}.
     */
    public void checkOwnChannels() {
        for (String chatId : distinctPublishChatIds()) {
            String username = telegramNotifier.getChatUsername(chatId);
            if (username == null) continue;
            TelegramClient.ChannelResult result = telegramClient.fetchChannel(username, ENGAGEMENT_SAMPLE_POSTS);
            if (!result.ok()) {
                log.warn("Свой канал @{} недоступен для чтения вовлечённости: {}", username, result.reason());
                continue;
            }
            recordFrom(username, result.items());
        }
    }

    /**
     * Sums views and per-emoji reactions across a batch of posts and records them as
     * gauges for that channel — a snapshot of current engagement, not a running total,
     * which is why these are gauges rather than counters.
     *
     * Called both for the app's own channels (above) and, from discovery, for each
     * source channel as it's scraped — same shape of question, same metrics, told apart
     * by the channel tag.
     */
    public void recordFrom(String channel, List<TelegramClient.TelegramMessage> messages) {
        int totalViews = 0;
        Map<String, Integer> totalReactions = new HashMap<>();
        for (TelegramClient.TelegramMessage msg : messages) {
            if (msg.views() != null) totalViews += msg.views();
            if (msg.reactions() != null) {
                msg.reactions().forEach((emoji, count) -> totalReactions.merge(emoji, count, Integer::sum));
            }
        }
        telegramMetrics.recordViews(channel, totalViews);
        telegramMetrics.recordReactions(channel, totalReactions);
    }

    /** Distinct chat_ids currently configured as SOME enabled search's public-post
     *  destination — several searches can share one channel, so callers that poll per
     *  chat_id only do it once each. */
    private Set<String> distinctPublishChatIds() {
        Set<String> chatIds = new HashSet<>();
        for (SearchConfig search : searchRepo.findAllEnabled()) {
            if (search.getChatId() != null && !search.getChatId().isBlank()) {
                chatIds.add(search.getChatId());
            }
        }
        return chatIds;
    }
}
