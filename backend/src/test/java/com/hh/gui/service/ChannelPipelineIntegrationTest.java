package com.hh.gui.service;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.ai.AiProviderManager;
import com.hh.gui.ai.PromptCapturingAnalyzer;
import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.TelegramClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end run of the channel pipeline against the real Spring context and a real
 * (H2) database: Telegram posts in, published channel message out.
 *
 * Everything between those two points is production code — SearchProfileFactory builds
 * the job from the DB row, VacancyDiscovery parses and saves, VacancyAiAnalyzer builds
 * the real prompt and parses a real response shape, dedup runs, ChannelPublisher formats
 * and sends. Only the four external boundaries are faked: the Telegram sidecar, the LLM
 * HTTP call, the Bot API, and (implicitly) the hh.ru scraper, which the Telegram Path B
 * flow never touches.
 *
 * This exists because the unit suite proved unable to catch wiring breaks. Verified by
 * mutation: deleting {@code job.kind = search.getKind()} in SearchProfileFactory — the
 * one line that makes the whole PERSONAL/EDITORIAL split work — left all 432 unit tests
 * green, as did deleting the {@code channelPublisher.send(...)} call that publishes
 * anything at all. Both are covered here.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.telegram.public-format-enabled=true")
@Import(ChannelPipelineIntegrationTest.Fakes.class)
class ChannelPipelineIntegrationTest {

    private static final String CHANNEL = "testchan";
    private static final String CHAT_ID = "-1009999";

    @TestConfiguration
    static class Fakes {
        @Bean @Primary
        TelegramClient telegramClient() { return new CannedTelegramClient(); }

        @Bean @Primary
        VacancyAiAnalyzer analyzer(RuntimeConfig config, AiProviderManager providers, AiMetrics metrics,
                                    com.hh.gui.client.CurrencyRateService currencyRates) {
            return new PromptCapturingAnalyzer(config, providers, metrics, currencyRates);
        }

        @Bean @Primary
        TelegramNotifier notifier() { return new CapturingNotifier(); }
    }

    /** Two Path B posts (no hh.ru link) — nothing to scrape, so the flow stays self-contained. */
    static class CannedTelegramClient extends TelegramClient {
        @Override
        public ChannelResult fetchChannel(String username, int limit) {
            return new ChannelResult(true, null, List.of(
                new TelegramMessage("tg_" + CHANNEL + "_1",
                    "Оператор чата поддержки\n\nЗарплата: от 45000 руб.\nОбязанности: отвечать клиентам в чате\nОтклик: hr@example.com",
                    "2026-08-16T09:00:00.000Z", "https://t.me/" + CHANNEL + "/1", CHANNEL, "telegram",
                    120, Map.of("❤", 3)),
                new TelegramMessage("tg_" + CHANNEL + "_2",
                    "Ассистент руководителя\n\nЗарплата: от 60000 руб.\nОбязанности: планирование и документы\nПишите в лс @hr_person",
                    "2026-08-16T09:05:00.000Z", "https://t.me/" + CHANNEL + "/2", CHANNEL, "telegram",
                    80, Map.of())));
        }
    }

    static class CapturingNotifier extends TelegramNotifier {
        final List<String> channelPosts = new ArrayList<>();
        final List<String> personalMessages = new ArrayList<>();
        @Override
        public boolean sendViaChannelBot(String message, String targetChatId) {
            channelPosts.add(message);
            return true;
        }
        @Override
        public boolean send(String message, String targetChatId) {
            personalMessages.add(message);
            return true;
        }
    }

    @Autowired private VacancyPipelineService pipeline;
    @Autowired private SearchProfileFactory profileFactory;
    @Autowired private VacancyAiAnalyzer analyzer;
    @Autowired private TelegramNotifier notifier;
    @Autowired private RuntimeConfig runtimeConfig;
    @Autowired private ChannelPublisher publisher;
    @Autowired private JdbcTemplate jdbc;

    private PromptCapturingAnalyzer llm() { return (PromptCapturingAnalyzer) analyzer; }
    private CapturingNotifier sent() { return (CapturingNotifier) notifier; }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM vacancies");
        jdbc.update("DELETE FROM searches");
        jdbc.update("DELETE FROM users");
        llm().prompts.clear();
        sent().channelPosts.clear();
        sent().personalMessages.clear();
        runtimeConfig.setChannelNotificationsEnabled(true);
    }

    /**
     * Moves whatever is queued into the past so the due-publish tick picks it up now.
     *
     * Without this the test would depend on the time of day it runs: enqueue pushes any
     * slot landing outside 07:00–23:00 to the next morning (correct production behaviour
     * — the channel shouldn't post at 3am), so a night-time CI run would find nothing due
     * and fail while the same test passed in the afternoon.
     */
    private void fastForwardQueue() {
        jdbc.update("UPDATE vacancies SET queued_publish_at = '2020-01-01T00:00:00Z' "
            + "WHERE queued_publish_at IS NOT NULL AND notified = 0");
    }

    /** A channel search exactly as production stores one: EDITORIAL, public format, no personal profile. */
    private long insertEditorialSearch() {
        jdbc.update("INSERT INTO users (id, username, password_hash, display_name, role, city, created_at) "
            + "VALUES (1, 'admin', 'x', 'Admin', 'admin', '', '2026-08-16')");
        jdbc.update("INSERT INTO searches (id, user_id, name, queries, ai_notes, enabled, created_at, updated_at, "
            + "is_global, chat_id, public_format, search_kind, telegram_channels, publish_pace_minutes) "
            + "VALUES (100, 1, 'Без техстека', '[]', 'Берём вакансии без требований к техстеку', TRUE, "
            + "'2026-08-16', '2026-08-16', TRUE, ?, 1, 'EDITORIAL', ?, 5)",
            CHAT_ID, "[\"" + CHANNEL + "\"]");
        return 100L;
    }

    @Test
    void telegramPostsBecomeAPublishedChannelMessage() {
        long searchId = insertEditorialSearch();
        SearchJob job = profileFactory.buildForSearchId(searchId).orElseThrow();

        pipeline.runFullPipelineFromTelegram(job, job.telegramChannels);
        // Как в проде: с publish_pace_minutes одобренное встаёт в очередь, а уходит
        // следующим тиком планировщика. doPublishDueQueued вместо публичного метода —
        // тот молча ничего не делает вне окна 07:00–23:00, а тесты идут круглосуточно.
        fastForwardQueue();
        publisher.doPublishDueQueued(50);

        // 1. Кандидаты сохранены как Path B (скрейпить нечего — пост и есть источник).
        List<Map<String, Object>> saved = jdbc.queryForList(
            "SELECT hh_id, source, scrape_status, title, company FROM vacancies ORDER BY hh_id");
        assertEquals(2, saved.size(), "оба поста должны стать кандидатами");
        assertEquals("telegram", saved.get(0).get("source"));
        assertEquals("ok", saved.get(0).get("scrape_status"));
        assertEquals("Оператор чата поддержки", saved.get(0).get("title"), "заголовок разобран из текста поста");

        // 2. Модели задан РЕДАКЦИОННЫЙ вопрос — то самое звено, обрыв которого unit-тесты не ловили.
        String prompt = llm().onlyPrompt();
        assertTrue(prompt.contains("редактор Telegram-канала"), "канал должен получить редакционный бриф");
        assertTrue(prompt.contains("Берём вакансии без требований к техстеку"), "политика канала — в промпте");
        assertFalse(prompt.contains("ПРОФИЛЬ:"), "у канала нет профиля кандидата");

        // 3. Пост реально ушёл в канал, одним сообщением, в публичном формате.
        assertEquals(1, sent().channelPosts.size(), "одобренные вакансии должны уйти в канал");
        String post = sent().channelPosts.get(0);
        assertTrue(post.contains("Оператор чата поддержки"), post);
        assertTrue(post.contains("Ассистент руководителя"), post);
        assertFalse(post.contains("85%"), "внутренний скоринг не должен утекать в публичный канал");
        assertTrue(sent().personalMessages.isEmpty(), "личный отчёт для канального поиска слать не нужно");

        // 4. Отправленное помечено в БД — повторной публикации на следующем тике не будет.
        Integer notified = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies WHERE notified = 1", Integer.class);
        assertEquals(2, notified, "опубликованные вакансии должны быть помечены notified");
    }

    @Test
    void deadSelfLinkReplacedByContactFromThePost() {
        // Сквозная проверка находки аудита: ссылка «на сам этот пост» бесполезна читателю,
        // и контакт должен подставляться на всём пути, а не только в юнит-тесте форматтера.
        long searchId = insertEditorialSearch();
        SearchJob job = profileFactory.buildForSearchId(searchId).orElseThrow();

        pipeline.runFullPipelineFromTelegram(job, job.telegramChannels);
        fastForwardQueue();
        publisher.doPublishDueQueued(50);

        String post = sent().channelPosts.get(0);
        assertTrue(post.contains("hr@example.com"), "email из поста должен стать адресом отклика: " + post);
        assertTrue(post.contains("t.me/hr_person"), "личный контакт рядом с «пишите в лс» тоже: " + post);
        assertFalse(post.contains("t.me/" + CHANNEL + "/1"), "ссылка на сам пост — тупик для читателя");
    }

    @Test
    void rerunningTheSamePostsPublishesNothingTwice() {
        long searchId = insertEditorialSearch();
        SearchJob job = profileFactory.buildForSearchId(searchId).orElseThrow();

        pipeline.runFullPipelineFromTelegram(job, job.telegramChannels);
        fastForwardQueue();
        publisher.doPublishDueQueued(50);
        int afterFirst = sent().channelPosts.size();
        pipeline.runFullPipelineFromTelegram(job, job.telegramChannels);
        fastForwardQueue();
        publisher.doPublishDueQueued(50);

        assertEquals(afterFirst, sent().channelPosts.size(),
            "повторный проход по тем же постам не должен публиковать их снова");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies", Integer.class);
        assertEquals(2, rows, "и не должен заводить дубли в базе");
    }

    @Test
    void channelNotificationsOff_savesCandidatesButPublishesNothing() {
        runtimeConfig.setChannelNotificationsEnabled(false);
        long searchId = insertEditorialSearch();
        SearchJob job = profileFactory.buildForSearchId(searchId).orElseThrow();

        pipeline.runFullPipelineFromTelegram(job, job.telegramChannels);
        fastForwardQueue();
        publisher.doPublishDueQueued(50);

        assertTrue(sent().channelPosts.isEmpty(), "выключенные уведомления канала должны останавливать публикацию");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies", Integer.class);
        assertEquals(2, rows, "но сбор кандидатов при этом продолжается");
        Integer notified = jdbc.queryForObject("SELECT COUNT(*) FROM vacancies WHERE notified = 1", Integer.class);
        assertEquals(0, notified, "неотправленное не должно помечаться отправленным");
    }
}
