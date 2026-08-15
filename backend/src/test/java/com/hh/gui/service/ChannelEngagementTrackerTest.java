package com.hh.gui.service;

import com.hh.gui.client.TelegramClient;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.repository.SearchRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChannelEngagementTrackerTest {

    private static class FakeSearchRepo extends SearchRepository {
        List<SearchConfig> enabled = new ArrayList<>();
        FakeSearchRepo() { super(null); }
        @Override
        public List<SearchConfig> findAllEnabled() { return enabled; }
    }

    private static class FakeNotifier extends TelegramNotifier {
        final List<String> queriedChatIds = new ArrayList<>();
        final Map<String, Integer> counts;
        Map<String, String> usernames = Map.of();
        FakeNotifier(Map<String, Integer> counts) { this.counts = counts; }
        @Override
        public Integer getChatMemberCount(String targetChatId) {
            queriedChatIds.add(targetChatId);
            return counts.get(targetChatId);
        }
        @Override
        public String getChatUsername(String targetChatId) { return usernames.get(targetChatId); }
    }

    private static class FakeTelegramClient extends TelegramClient {
        final Map<String, ChannelResult> byChannel;
        FakeTelegramClient(Map<String, ChannelResult> byChannel) { this.byChannel = byChannel; }
        @Override
        public ChannelResult fetchChannel(String username, int limit) {
            return byChannel.getOrDefault(username, new ChannelResult(true, null, List.of()));
        }
    }

    private static SearchConfig searchWithChatId(String chatId) {
        SearchConfig s = new SearchConfig();
        s.setChatId(chatId);
        return s;
    }

    private static TelegramClient.TelegramMessage msg(String id, Integer views, Map<String, Integer> reactions) {
        return new TelegramClient.TelegramMessage(id, "текст", "2026-08-15T09:00:00.000Z",
            "https://t.me/chan/" + id, "chan", "telegram", views, reactions);
    }

    // ── подписчики ──

    @Test
    void checkSubscribers_dedupesSharedChatId_pollsOncePerDistinctChannel() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId("-100111"), searchWithChatId("-100111"), searchWithChatId("-100222"));
        FakeNotifier notifier = new FakeNotifier(Map.of("-100111", 42, "-100222", 7));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ChannelEngagementTracker(searchRepo, new FakeTelegramClient(Map.of()), notifier, new TelegramMetrics(registry))
            .checkSubscribers();

        assertEquals(2, notifier.queriedChatIds.size(), "два поиска на один и тот же chat_id должны опросить его только один раз");
        assertEquals(42.0, registry.find("telegram_channel_subscribers").tag("channel", "-100111").gauge().value());
        assertEquals(7.0, registry.find("telegram_channel_subscribers").tag("channel", "-100222").gauge().value());
    }

    @Test
    void checkSubscribers_blankChatId_skipped() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId(""), searchWithChatId(null));
        FakeNotifier notifier = new FakeNotifier(Map.of());

        new ChannelEngagementTracker(searchRepo, new FakeTelegramClient(Map.of()), notifier,
            new TelegramMetrics(new SimpleMeterRegistry())).checkSubscribers();

        assertTrue(notifier.queriedChatIds.isEmpty());
    }

    @Test
    void checkSubscribers_apiReturnsNothing_gaugeNotRecordedRatherThanZero() {
        // A failed poll must leave the previous value standing, not overwrite it with 0 —
        // a dashboard reading "0 subscribers" would be a lie about the channel.
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId("-100111"));
        FakeNotifier notifier = new FakeNotifier(Map.of()); // getChatMemberCount -> null
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ChannelEngagementTracker(searchRepo, new FakeTelegramClient(Map.of()), notifier, new TelegramMetrics(registry))
            .checkSubscribers();

        assertNull(registry.find("telegram_channel_subscribers").gauge());
    }

    // ── свой канал ──

    @Test
    void checkOwnChannels_recordsUnderChannelsOwnUsername_notChatId() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId("-1004333110303"));
        FakeNotifier notifier = new FakeNotifier(Map.of());
        notifier.usernames = Map.of("-1004333110303", "remotevibe");
        FakeTelegramClient tg = new FakeTelegramClient(Map.of("remotevibe", new TelegramClient.ChannelResult(
            true, null, List.of(msg("1", 10, Map.of("❤", 2)), msg("2", 15, Map.of("🔥", 1))))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ChannelEngagementTracker(searchRepo, tg, notifier, new TelegramMetrics(registry)).checkOwnChannels();

        assertEquals(25.0, registry.find("telegram_channel_views_recent").tag("channel", "remotevibe").gauge().value());
        assertEquals(2.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "remotevibe").tag("emoji", "❤").gauge().value());
    }

    @Test
    void checkOwnChannels_noPublicUsername_skippedWithoutError() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId("-100999"));
        FakeNotifier notifier = new FakeNotifier(Map.of()); // приватный канал, username не резолвится

        ChannelEngagementTracker tracker = new ChannelEngagementTracker(searchRepo,
            new FakeTelegramClient(Map.of()), notifier, new TelegramMetrics(new SimpleMeterRegistry()));

        assertDoesNotThrow(tracker::checkOwnChannels);
    }

    @Test
    void checkOwnChannels_scraperUnavailable_skipsThatChannelWithoutRecording() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        searchRepo.enabled = List.of(searchWithChatId("-100999"));
        FakeNotifier notifier = new FakeNotifier(Map.of());
        notifier.usernames = Map.of("-100999", "somechan");
        FakeTelegramClient tg = new FakeTelegramClient(Map.of("somechan",
            new TelegramClient.ChannelResult(false, "scraper_down", List.of())));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ChannelEngagementTracker(searchRepo, tg, notifier, new TelegramMetrics(registry)).checkOwnChannels();

        assertNull(registry.find("telegram_channel_views_recent").gauge(),
            "недоступный канал не должен записываться как канал с нулевой вовлечённостью");
    }

    // ── агрегация ──

    @Test
    void recordFrom_sumsViewsAndReactionsAcrossPosts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChannelEngagementTracker tracker = new ChannelEngagementTracker(new FakeSearchRepo(),
            new FakeTelegramClient(Map.of()), new FakeNotifier(Map.of()), new TelegramMetrics(registry));

        tracker.recordFrom("testchan", List.of(
            msg("1", 10, Map.of("❤", 2)),
            msg("2", 15, Map.of("❤", 1, "🔥", 3))));

        assertEquals(25.0, registry.find("telegram_channel_views_recent").tag("channel", "testchan").gauge().value());
        assertEquals(3.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "testchan").tag("emoji", "❤").gauge().value());
        assertEquals(3.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "testchan").tag("emoji", "🔥").gauge().value());
    }

    @Test
    void recordFrom_postsWithoutViewsOrReactions_treatedAsZeroNotCrash() {
        // tg-scraper reads views/reactions best-effort through an undocumented internal
        // API, so nulls are an expected shape here, not a defect.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChannelEngagementTracker tracker = new ChannelEngagementTracker(new FakeSearchRepo(),
            new FakeTelegramClient(Map.of()), new FakeNotifier(Map.of()), new TelegramMetrics(registry));

        tracker.recordFrom("testchan", List.of(msg("1", null, null), msg("2", 5, Map.of())));

        assertEquals(5.0, registry.find("telegram_channel_views_recent").tag("channel", "testchan").gauge().value());
    }
}
