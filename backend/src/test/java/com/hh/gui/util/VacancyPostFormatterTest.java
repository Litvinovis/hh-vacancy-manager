package com.hh.gui.util;

import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VacancyPostFormatterTest {

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

    // ── the drift this class exists to prevent ──

    @Test
    void reportEntry_selfLink_usesContactToo_notJustPublicPost() {
        // The regression that motivated merging the two formatters: the dead-self-link
        // fix had landed only in the public post, so the personal digest kept printing
        // "🔗 https://t.me/<channel>/<id>" — a link back to the post the reader is
        // already looking at. Both shapes must now resolve it the same way.
        Vacancy v = telegramSelfLinked("Фонд ищет специалиста.\n\nОтклик:\n sasha@fond-igra.ru");

        String entry = VacancyPostFormatter.reportEntry(v);
        assertTrue(entry.contains("📧 sasha@fond-igra.ru"), entry);
        assertFalse(entry.contains("t.me/freelancce/15611"), entry);

        String post = VacancyPostFormatter.publicPost(v);
        assertTrue(post.contains("📧 sasha@fond-igra.ru"), post);
        assertFalse(post.contains("t.me/freelancce/15611"), post);
    }

    @Test
    void bothShapes_shareIdenticalFieldPreparation() {
        Vacancy v = hhLinked();
        String entry = VacancyPostFormatter.reportEntry(v);
        String post = VacancyPostFormatter.publicPost(v);
        for (String shared : new String[]{"Менеджер по закупкам", "KURSOVA RECRUIT", "анализ поставщиков"}) {
            assertTrue(entry.contains(shared), "нет в отчёте: " + shared);
            assertTrue(post.contains(shared), "нет в публичном посте: " + shared);
        }
    }

    // ── shape-specific behaviour that must NOT be unified away ──

    @Test
    void reportEntry_carriesScore_publicPostNeverDoes() {
        Vacancy v = hhLinked();
        assertTrue(VacancyPostFormatter.reportEntry(v).contains("[75%]"));
        assertFalse(VacancyPostFormatter.publicPost(v).contains("75%"),
            "внутренний скоринг не должен утекать в публичный канал");
    }

    @Test
    void reportEntry_realUrl_keepsPlainLinkMarker() {
        assertTrue(VacancyPostFormatter.reportEntry(hhLinked()).contains("🔗 https://hh.ru/vacancy/136268015"));
    }

    @Test
    void publicPost_realUrl_keepsArrowMarker() {
        assertTrue(VacancyPostFormatter.publicPost(hhLinked()).contains("👉 https://hh.ru/vacancy/136268015"));
    }

    @Test
    void publicPost_noveltyLineRenderedWhenColourAndNoteBothPresent() {
        Vacancy v = hhLinked();
        v.setNoveltyColor("green");
        v.setNoveltyNote("нестандартный формат");
        assertTrue(VacancyPostFormatter.publicPost(v).contains("🟢 Нестандартный формат"));
    }

    @Test
    void publicPost_noveltyColourWithoutNote_lineOmitted() {
        Vacancy v = hhLinked();
        v.setNoveltyColor("green");
        assertFalse(VacancyPostFormatter.publicPost(v).contains("🟢"));
    }

    // ── shared edge cases ──

    @Test
    void missingCompany_fallsBackToPlaceholderInBothShapes() {
        Vacancy v = hhLinked();
        v.setCompany(null);
        assertTrue(VacancyPostFormatter.reportEntry(v).contains("компания не указана"));
        assertTrue(VacancyPostFormatter.publicPost(v).contains("компания не указана"));
    }

    @Test
    void htmlInFieldsEscaped_soTelegramParseModeCannotBreak() {
        Vacancy v = hhLinked();
        v.setTitle("Разработчик <script>alert(1)</script>");
        v.setCompany("Рога & Копыта");
        String post = VacancyPostFormatter.publicPost(v);
        assertTrue(post.contains("&lt;script&gt;"), post);
        assertTrue(post.contains("Рога &amp; Копыта"), post);
    }

    @Test
    void blankUrlAndNoContact_producesNoApplyLineRatherThanDanglingMarker() {
        Vacancy v = telegramSelfLinked("Описание без контактов");
        v.setUrl("");
        assertFalse(VacancyPostFormatter.publicPost(v).contains("👉"));
        assertFalse(VacancyPostFormatter.reportEntry(v).contains("🔗"));
    }

    @Test
    void overlongTitleTruncated() {
        Vacancy v = hhLinked();
        v.setTitle("Оператор ".repeat(40));
        assertTrue(VacancyPostFormatter.publicPost(v).contains("…"));
    }
}
