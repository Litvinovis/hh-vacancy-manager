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
    void reportEntry_realUrl_keepsLinkMarker_wrappedAsAnchor() {
        String entry = VacancyPostFormatter.reportEntry(hhLinked());
        assertTrue(entry.contains("🔗 <a href=\"https://hh.ru/vacancy/136268015\">Откликнуться</a>"), entry);
    }

    @Test
    void publicPost_realUrl_keepsArrowMarker_wrappedAsAnchor() {
        String post = VacancyPostFormatter.publicPost(hhLinked());
        assertTrue(post.contains("👉 <a href=\"https://hh.ru/vacancy/136268015\">Откликнуться</a>"), post);
    }

    @Test
    void publicPost_longCyrillicUrl_hiddenInsideAnchorNotShownAsText() {
        // Live bug: kadrout.ru links with percent-encoded Cyrillic slugs ran past 300
        // characters — printed inline, that's a wall of "%D0%9A..." instead of a clean
        // line. The raw URL now only appears inside the href, never as visible text.
        Vacancy v = hhLinked();
        String uglyUrl = "https://kadrout.ru/vacancies/38030/%D0%BC%D0%B0%D1%80%D0%BA%D0%B5%D1%82%D0%BE%D0%BB%D0%BE%D0%B3?utm_source=tg";
        v.setUrl(uglyUrl);

        String post = VacancyPostFormatter.publicPost(v);
        assertTrue(post.contains("href=\"" + uglyUrl + "\""), post);
        assertTrue(post.contains("👉 <a href="), post);
        assertFalse(post.replaceAll("href=\"[^\"]*\"", "").contains("%D0"),
            "сырой URL не должен встречаться нигде, кроме href");
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
    void channelHandlePlaceholderCompany_treatedAsMissingNotShownVerbatim() {
        // Live bug: TelegramPostParser.employer() falls back to "@channel" when no real
        // employer is found — printed as-is, a reader sees "🏢 @vacancysmm" as if that
        // were the hiring company, which reads as a bug (you can't apply to an
        // @-handle). Same placeholder text as a genuinely missing company.
        Vacancy v = hhLinked();
        v.setCompany("@vacancysmm");

        String post = VacancyPostFormatter.publicPost(v);
        assertTrue(post.contains("компания не указана"), post);
        assertFalse(post.contains("@vacancysmm"), post);
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
    void selfLinkUrlAndNoContact_alsoProducesNoApplyLine_notTheDeadSelfLink() {
        // Found live: 65 of 359 sent Telegram-sourced posts had this exact shape (a
        // t.me self-link url, description with no extractable contact — e.g. "Исполнитель
        // найден в этом канале"). Unlike the blank-url case above, a non-blank self-link
        // url fell through past the contact check to the plain-url branch, printing
        // "Откликнуться" pointing at another channel's post — precisely the "links to
        // other channels" the self-link check exists to prevent in the first place.
        Vacancy v = telegramSelfLinked("Исполнитель найден в этом канале");
        String post = VacancyPostFormatter.publicPost(v);
        assertFalse(post.contains("t.me/freelancce"), post);
        assertFalse(post.contains("👉"), post);
        assertFalse(VacancyPostFormatter.reportEntry(v).contains("🔗"));
    }

    @Test
    void personalUsernameContact_alsoWrappedAsAnchor_notRawTMeLink() {
        Vacancy v = telegramSelfLinked("Для связи пишите: @some_recruiter");
        String post = VacancyPostFormatter.publicPost(v);
        assertTrue(post.contains("💬 <a href=\"https://t.me/some_recruiter\">Откликнуться</a>"), post);
    }

    @Test
    void overlongTitleTruncated() {
        Vacancy v = hhLinked();
        v.setTitle("Оператор ".repeat(40));
        assertTrue(VacancyPostFormatter.publicPost(v).contains("…"));
    }
}
