package com.hh.gui.content;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.service.TelegramNotifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramArticleMirrorTest {

    private static class FakeTelegram extends TelegramNotifier {
        final List<String> sent = new ArrayList<>(); final List<String> chats = new ArrayList<>();
        @Override public String sendViaChannelBotReturningId(String message, String chatId) {
            sent.add(message); chats.add(chatId); return "1";
        }
    }

    private static class FakeSearches extends SearchRepository {
        final List<SearchConfig> enabled = new ArrayList<>();
        FakeSearches() { super(null); }
        @Override public List<SearchConfig> findAllEnabled() { return enabled; }
    }

    private static SearchConfig search(String chatId, boolean publicFormat) {
        SearchConfig s = new SearchConfig(); s.setChatId(chatId); s.setPublicFormat(publicFormat); return s;
    }

    private static class FakeArticles extends com.hh.gui.repository.VkArticleRepository {
        final java.util.Map<Long, String> tg = new java.util.HashMap<>(); final List<VkArticle> pending = new ArrayList<>();
        FakeArticles() { super(null); }
        @Override public void setTgStatus(Long id, String status) { tg.put(id, status); }
        @Override public List<VkArticle> findTgPending(String since) { return pending.stream().filter(a -> "pending".equals(tg.get(a.getId()))).toList(); }
    }

    private static final java.time.Instant NOON_MSK = java.time.Instant.parse("2026-09-28T09:00:00Z");
    private static final java.time.Instant NIGHT_MSK = java.time.Instant.parse("2026-09-28T23:00:00Z");

    private static TelegramArticleMirror mirror(TelegramNotifier tg, SearchRepository s, RuntimeConfig c, FakeArticles a, java.time.Instant at) {
        TelegramArticleMirror m = new TelegramArticleMirror(tg, s, c, a);
        m.now = () -> at;
        return m;
    }

    private static long nextId = 1;
    private static VkArticle article(String kind, String body) {
        VkArticle a = new VkArticle(); a.setId(nextId++); a.setKind(kind); a.setTitle("t"); a.setBody(body); return a;
    }

    @Test
    void adapt_dropsHashtagsAndVkCallToAction_boldsHeadline_escapesHtml() {
        String vk = "Удалённая работа ассистентом: какие вакансии есть\n\nЗарплаты от 60 000 <₽> & выше.\n\nА вы где ищете?\n\n"
            + ArticleGenerator.SEO_CALL_TO_ACTION + "\n\n#удаленнаяработа #работанадому #ассистент";

        String tg = TelegramArticleMirror.adapt(vk);

        assertTrue(tg.startsWith("<b>Удалённая работа ассистентом: какие вакансии есть</b>\n\n"), tg);
        assertTrue(tg.contains("60 000 &lt;₽&gt; &amp; выше"), tg);
        assertFalse(tg.contains("#"), tg);
        assertFalse(tg.contains("вступайте"), tg);
        assertTrue(tg.endsWith("А вы где ищете?"), tg);
    }

    @Test
    void mirror_sendsToEachPublicChannelOnce_skipsPersonalChatsAndPolls() {
        FakeTelegram tg = new FakeTelegram(); FakeSearches searches = new FakeSearches();
        searches.enabled.addAll(List.of(search("-100", true), search("-100", true), search("555", false), search("-200", true)));
        TelegramArticleMirror mirror = mirror(tg, searches, new RuntimeConfig(), new FakeArticles(), NOON_MSK);

        mirror.mirror(article("poll", "Какую работу ищете?"));
        assertTrue(tg.sent.isEmpty(), "опрос в Telegram не дублируется");

        mirror.mirror(article(SeoTopics.KIND, "Заголовок\n\nТекст #вакансии"));
        assertEquals(List.of("-100", "-200"), tg.chats);
    }

    @Test
    void mirror_disabledInConfig_sendsNothing() {
        FakeTelegram tg = new FakeTelegram(); FakeSearches searches = new FakeSearches();
        searches.enabled.add(search("-100", true));
        RuntimeConfig config = new RuntimeConfig(); config.setTgArticlesEnabled(false);

        mirror(tg, searches, config, new FakeArticles(), NOON_MSK).mirror(article("article", "Заголовок\n\nТекст"));
        assertTrue(tg.sent.isEmpty());
    }

    // ── дослать после сбоя связи ──

    private static class FlakyTelegram extends FakeTelegram {
        boolean down = true;
        @Override public String sendViaChannelBotReturningId(String message, String chatId) {
            return down ? null : super.sendViaChannelBotReturningId(message, chatId);
        }
    }

    @Test
    void telegramDown_articleStaysPending_andIsSentWhenConnectionReturns() {
        // Живой случай 28.09.2026: связи с Telegram не было с 15:27 до 19:13, обзор потерялся
        FlakyTelegram tg = new FlakyTelegram(); FakeSearches searches = new FakeSearches();
        searches.enabled.add(search("-100", true));
        FakeArticles arts = new FakeArticles();
        TelegramArticleMirror m = mirror(tg, searches, new RuntimeConfig(), arts, NOON_MSK);
        VkArticle a = article(SeoTopics.KIND, "Заголовок\n\nТекст");
        arts.pending.add(a);

        m.mirror(a);
        assertEquals("pending", arts.tg.get(a.getId()));

        m.retryPending();
        assertEquals("pending", arts.tg.get(a.getId()), "связи всё ещё нет");

        tg.down = false;
        m.retryPending();
        assertEquals("sent", arts.tg.get(a.getId()));
        assertEquals(1, tg.sent.size());

        m.retryPending();
        assertEquals(1, tg.sent.size(), "отправленная второй раз не уходит");
    }

    @Test
    void outsideChannelWindow_waitsForMorning() {
        FakeTelegram tg = new FakeTelegram(); FakeSearches searches = new FakeSearches();
        searches.enabled.add(search("-100", true));
        FakeArticles arts = new FakeArticles();
        VkArticle a = article("article", "Заголовок\n\nТекст");

        mirror(tg, searches, new RuntimeConfig(), arts, NIGHT_MSK).mirror(a);

        assertTrue(tg.sent.isEmpty(), "02:00 по Москве — канал спит");
        assertEquals("pending", arts.tg.get(a.getId()));
    }
}
