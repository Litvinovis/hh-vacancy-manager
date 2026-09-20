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
            assertTrue(post.toLowerCase().contains(expected.toLowerCase()), "нет в посте: " + expected);
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
    void publicPost_realUrl_goesToFirstComment_notIntoPostBody() {
        // Внешняя ссылка в тексте режет охват в умной ленте — она уходит в первый комментарий
        String post = VkPostFormatter.publicPost(hhLinked());
        assertFalse(post.contains("https://hh.ru"), post);
        assertTrue(post.contains("в первом комментарии"), post);
        assertEquals("Откликнуться: https://hh.ru/vacancy/136268015", VkPostFormatter.applyComment(hhLinked()));
    }

    @Test
    void publicPost_selfLink_resolvesToContact_notDeadTelegramLink() {
        Vacancy v = telegramSelfLinked("Фонд ищет специалиста.\n\nОтклик:\n sasha@fond-igra.ru");

        String post = VkPostFormatter.publicPost(v);
        assertTrue(post.contains("sasha@fond-igra.ru"), post);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
        assertTrue(VkPostFormatter.applyComment(v).contains("sasha@fond-igra.ru"));
    }

    @Test
    void publicPost_selfLinkWithNoExtractableContact_printsNoApplyLineAtAll() {
        Vacancy v = telegramSelfLinked("Просто описание без каких-либо контактов внутри.");

        String post = VkPostFormatter.publicPost(v);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
        assertNull(VkPostFormatter.applyComment(v), "без контакта комментарий не нужен");
    }

    @Test
    void publicPost_placeholderCompany_lineIsOmitted_handleNeverLeaks() {
        // Заглушка «компания не указана» читателю ничего не даёт и делает пост похожим на
        // шаблон — строку компании просто не печатаем; @-хендл канала-источника тоже не утекает.
        Vacancy v = hhLinked();
        v.setCompany("@freelancce");

        String post = VkPostFormatter.publicPost(v);
        assertFalse(post.contains("компания не указана"), post);
        assertFalse(post.contains("@freelancce"), post);
    }

    @Test
    void publicPost_noveltyColor_rendersEmojiAndCapitalizedNote() {
        Vacancy v = hhLinked();
        v.setNoveltyColor("green");
        v.setNoveltyNote("нестандартный формат работы");

        String post = VkPostFormatter.publicPost(v);
        assertTrue(post.contains("Почему интересно: Нестандартный формат работы"), post);
        assertFalse(post.contains("🟢"), "эмодзи-маркеры убраны: умная лента считает их шаблонностью");
    }

    @Test
    void publicPost_neverExposesAiScore() {
        String post = VkPostFormatter.publicPost(hhLinked());
        assertFalse(post.contains("75%"), "внутренний скоринг не должен утекать в публичный пост VK");
    }

    @Test
    void publicPost_firstLineIsHook_withSalaryAndRemote() {
        Vacancy v = hhLinked();
        v.setSalaryFrom(80000); v.setSalaryTo(null); v.setCurrency("RUR");
        String first = VkPostFormatter.publicPost(v).split("\n")[0];
        assertTrue(first.startsWith("Менеджер по закупкам — "), first);
        assertTrue(first.contains("80 000"), "зарплата в первой строке — её видно в ленте до «Показать полностью»: " + first);
        assertTrue(first.endsWith("удалённо"), first);
    }

    @Test
    void publicPost_hashtags_generalPlusKindPlusCommunity_neverMoreThanThree() {
        Vacancy v = hhLinked();
        v.setTitle("Ассистент руководителя");
        String post = VkPostFormatter.publicPost(v, "remotevibe");
        String last = post.substring(post.lastIndexOf('\n') + 1);
        assertEquals("#удалённаяработа #ассистент #вакансии@remotevibe", last);
        assertEquals(3, last.split(" ").length, "в ВК больше трёх хэштегов не работают");
    }

    @Test
    void publicPost_unknownKind_noKindTag_noCommunity_noGuessing() {
        Vacancy v = hhLinked();
        v.setTitle("Космонавт");
        String post = VkPostFormatter.publicPost(v, null);
        assertTrue(post.endsWith("#удалённаяработа"), post);
    }

    @Test
    void kindTag_recognisesCommonRemoteRoles() {
        assertEquals("#поддержка", VkPostFormatter.kindTag("Оператор чата поддержки"));
        assertEquals("#контент", VkPostFormatter.kindTag("SMM-менеджер / контент-креатор"));
        assertEquals("#маркетплейсы", VkPostFormatter.kindTag("Менеджер Wildberries"));
        assertNull(VkPostFormatter.kindTag("Инженер-проектировщик"));
    }
}
