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

    private static VkArticle article(String kind, String body) {
        VkArticle a = new VkArticle(); a.setKind(kind); a.setTitle("t"); a.setBody(body); return a;
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
        TelegramArticleMirror mirror = new TelegramArticleMirror(tg, searches, new RuntimeConfig());

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

        new TelegramArticleMirror(tg, searches, config).mirror(article("article", "Заголовок\n\nТекст"));
        assertTrue(tg.sent.isEmpty());
    }
}
