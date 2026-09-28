package com.hh.gui.content;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.VkArticleRepository;
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

    /** Сколько после выхода в VK статья ещё ждёт отправки в Telegram — дальше она уже не новость. */
    static final java.time.Duration RETRY_HORIZON = java.time.Duration.ofHours(48);

    private final TelegramNotifier telegram;
    private final SearchRepository searches;
    private final RuntimeConfig config;
    private final VkArticleRepository articles;
    /** Текущее время для проверки окна канала; в тестах подменяется. */
    java.util.function.Supplier<java.time.Instant> now = java.time.Instant::now;

    public TelegramArticleMirror(TelegramNotifier telegram, SearchRepository searches, RuntimeConfig config,
                                 VkArticleRepository articles) {
        this.telegram = telegram;
        this.searches = searches;
        this.config = config;
        this.articles = articles;
    }

    /**
     * Статья вышла в VK — отправить её и в Telegram. Сначала она помечается pending: если
     * связи с Telegram нет (28.09.2026 обрыв на 4 часа, обзор так и не дошёл) или сейчас
     * ночь вне окна канала, её дошлёт {@link #retryPending()}.
     */
    public void mirror(VkArticle a) {
        if (!config.isTgArticlesEnabled() || a.isPoll() || a.getBody() == null || a.getBody().isBlank()) return;
        if (adapt(a.getBody()).isBlank()) return;
        a.setTgStatus("pending");
        articles.setTgStatus(a.getId(), "pending");
        trySend(a);
    }

    /** Дослать статьи, которые не ушли в Telegram: раз в 5 минут, только в окне канала. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT5M")
    public void retryPending() {
        if (!config.isTgArticlesEnabled()) return;
        for (VkArticle a : articles.findTgPending(now.get().minus(RETRY_HORIZON).toString())) {
            if (!trySend(a)) return;   // связи всё ещё нет — остальные подождут следующего тика
        }
    }

    /**
     * Отправляет в каналы публичных поисков; true — ушла во все и помечена sent. Каналов на
     * деле один; если бы часть отправок прошла, повтор задублировал бы её в прошедших — это
     * лучше, чем потерять статью в тех, что не прошли.
     */
    boolean trySend(VkArticle a) {
        if (com.hh.gui.service.ChannelPublisher.isOutsidePublishWindow(now.get())) return false;
        String message = adapt(a.getBody());
        boolean allSent = true;
        for (String chatId : channelChatIds()) {
            String id = telegram.sendViaChannelBotReturningId(message, chatId);
            if (id != null) {
                log.info("Статья «{}» продублирована в Telegram {} (message_id={})", a.getTitle(), chatId, id.isEmpty() ? "?" : id);
            } else {
                log.warn("Статья «{}» не ушла в Telegram {} — повторим, когда связь вернётся", a.getTitle(), chatId);
                allSent = false;
            }
        }
        if (allSent) {
            a.setTgStatus("sent");
            articles.setTgStatus(a.getId(), "sent");
        }
        return allSent;
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
