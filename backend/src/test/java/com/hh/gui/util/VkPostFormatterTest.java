package com.hh.gui.util;

import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VkPostFormatterTest {

    private Vacancy telegramSelfLinked(String description) {
        Vacancy v = new Vacancy();
        v.setHhId("tg_freelancce_15611");
        v.setTitle("Креатор в фонд «Игра»");
        v.setCompany("@freelancce");
        v.setAiScore(85);
        v.setAiReason("разрабатывает креативные кампании");
        v.setUrl("https://t.me/freelancce/15611");
        v.setDescription(description);
        return v;
    }

    private Vacancy hhLinked() {
        Vacancy v = new Vacancy();
        v.setHhId("136268015");
        v.setTitle("Менеджер по закупкам");
        v.setCompany("KURSOVA RECRUIT");
        v.setAiScore(75);
        v.setAiReason("анализ поставщиков");
        v.setUrl("https://hh.ru/vacancy/136268015");
        v.setDescription("Отклик: hr@kursova.ru");
        return v;
    }

    @Test
    void publicPost_containsCoreFields_sharedWithTelegramFormatter() {
        String post = VkPostFormatter.publicPost(hhLinked());
        for (String expected : new String[]{"Менеджер по закупкам", "KURSOVA RECRUIT", "анализ поставщиков"}) {
            assertTrue(post.contains(expected), "нет в посте: " + expected);
        }
    }

    @Test
    void publicPost_neverContainsHtmlMarkup() {
        // VK wall.post has no HTML/Markdown rendering — a literal "<b>"/"<a href" would
        // show up verbatim in the post text instead of being rendered as markup.
        String post = VkPostFormatter.publicPost(hhLinked());
        assertFalse(post.contains("<"), post);
        assertFalse(post.contains(">"), post);
    }

    @Test
    void publicPost_realUrl_printedAsBareLinkForVkAutoLinkification() {
        String post = VkPostFormatter.publicPost(hhLinked());
        assertTrue(post.contains("👉 Откликнуться: https://hh.ru/vacancy/136268015"), post);
    }

    @Test
    void publicPost_selfLink_resolvesToContact_notDeadTelegramLink() {
        Vacancy v = telegramSelfLinked("Фонд ищет специалиста.\n\nОтклик:\n sasha@fond-igra.ru");

        String post = VkPostFormatter.publicPost(v);
        assertTrue(post.contains("📧 sasha@fond-igra.ru"), post);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
    }

    @Test
    void publicPost_selfLinkWithNoExtractableContact_printsNoApplyLineAtAll() {
        Vacancy v = telegramSelfLinked("Просто описание без каких-либо контактов внутри.");

        String post = VkPostFormatter.publicPost(v);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
        assertFalse(post.contains("👉"), post);
    }

    @Test
    void publicPost_placeholderCompany_fallsBackToNotSpecified_sameAsTelegramFormatter() {
        Vacancy v = hhLinked();
        v.setCompany("@freelancce");

        String post = VkPostFormatter.publicPost(v);
        assertTrue(post.contains("компания не указана"), post);
    }

    @Test
    void publicPost_noveltyColor_rendersEmojiAndCapitalizedNote() {
        Vacancy v = hhLinked();
        v.setNoveltyColor("green");
        v.setNoveltyNote("нестандартный формат работы");

        String post = VkPostFormatter.publicPost(v);
        assertTrue(post.contains("🟢 Нестандартный формат работы"), post);
    }

    @Test
    void publicPost_neverExposesAiScore() {
        String post = VkPostFormatter.publicPost(hhLinked());
        assertFalse(post.contains("75%"), "внутренний скоринг не должен утекать в публичный пост VK");
    }
}
