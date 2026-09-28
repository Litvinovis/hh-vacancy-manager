package com.hh.gui.content;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.service.TelegramNotifier;
import com.hh.gui.util.VacancyPostFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Статьи и обзоры, вышедшие в VK, — ещё и в Telegram-канал, для разнообразия ленты (28.09.2026).
 *
 * Текст адаптируется: в Telegram нет поиска по постам, поэтому хэштеги там — просто шум, а
 * призыв вступить в сообщество VK читателю канала ни к чему — он уже подписан. Первая строка
 * становится жирным заголовком. Опросы не зеркалируются: вариант ответа в VK Telegram не покажет.
 */
@Component
public class TelegramArticleMirror {

    private static final Logger log = LoggerFactory.getLogger(TelegramArticleMirror.class);

    private final TelegramNotifier telegram;
    private final SearchRepository searches;
    private final RuntimeConfig config;

    public TelegramArticleMirror(TelegramNotifier telegram, SearchRepository searches, RuntimeConfig config) {
        this.telegram = telegram;
        this.searches = searches;
        this.config = config;
    }

    /** Отправить статью в каналы публичных поисков; сбой только логируется — пост в VK уже вышел. */
    public void mirror(VkArticle a) {
        if (!config.isTgArticlesEnabled() || a.isPoll() || a.getBody() == null || a.getBody().isBlank()) return;
        String message = adapt(a.getBody());
        if (message.isBlank()) return;
        for (String chatId : channelChatIds()) {
            String id = telegram.sendViaChannelBotReturningId(message, chatId);
            if (id != null) {
                log.info("Статья «{}» продублирована в Telegram {} (message_id={})", a.getTitle(), chatId, id.isEmpty() ? "?" : id);
            } else {
                log.warn("Статья «{}» не ушла в Telegram {}", a.getTitle(), chatId);
            }
        }
    }

    /** Текст для Telegram (HTML): без хэштегов и призыва в VK, первая строка — заголовок. */
    static String adapt(String vkText) {
        String text = vkText.replace(ArticleGenerator.SEO_CALL_TO_ACTION, "")
            .replaceAll("(?U)(^|\\s)#[\\w@.]+", "$1")
            .replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .strip();
        if (text.isEmpty()) return "";
        int nl = text.indexOf('\n');
        String headline = nl < 0 ? text : text.substring(0, nl);
        String rest = nl < 0 ? "" : text.substring(nl);
        return "<b>" + VacancyPostFormatter.escapeHtml(headline.strip()) + "</b>" + VacancyPostFormatter.escapeHtml(rest);
    }

    /** Те же каналы, куда уходят публичные посты вакансий: chat_id включённых поисков, без повторов. */
    private Set<String> channelChatIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SearchConfig s : searches.findAllEnabled()) {
            // Только публичные каналы: личный отчёт владельцу поиска статьи не нужны
            if (s.isPublicFormat() && s.getChatId() != null && !s.getChatId().isBlank()) ids.add(s.getChatId());
        }
        return ids;
    }
}
