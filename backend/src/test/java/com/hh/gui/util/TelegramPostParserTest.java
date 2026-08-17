package com.hh.gui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the post-parsing rules. Before the parser was extracted these
 * could only be exercised through discoverFromTelegram — meaning a fake Telegram
 * client, a fake repository and a 12-argument service constructor, all to assert on
 * a regex. Each case below is one of the live channel quirks the rules exist for.
 */
class TelegramPostParserTest {

    // ── title ──

    @Test
    void title_skipsLeadingHashtagLine() {
        assertEquals("SMM-специалист в онлайн-школу",
            TelegramPostParser.title("#вакансия #smm #удаленно\n\nSMM-специалист в онлайн-школу\n\nЗадачи: ..."));
    }

    @Test
    void title_stripsMarkdownHeadingAndCyrillicVacancyPrefix() {
        // CASE_INSENSITIVE alone doesn't fold Cyrillic in Java — capital "В" needs UNICODE_CASE.
        assertEquals("Асессор", TelegramPostParser.title("### Вакансия: Асессор\n\nКаждый день..."));
    }

    @Test
    void title_stripsZeroWidthSpaceTelegramPrepends() {
        assertEquals("Оператор чата", TelegramPostParser.title("​Оператор чата\n\nОписание"));
    }

    @Test
    void title_longSingleLine_truncatedTo150() {
        String longLine = "Оператор ".repeat(40);
        String result = TelegramPostParser.title(longLine);
        assertEquals(150, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void title_longRunOnSentence_cutsAtSentenceBoundaryNotMidClause() {
        // Live bug: a raw marketing-paragraph title with no natural break before 150
        // chars used to slice mid-word with "..." — "...с 95%...". A sentence-ending
        // punctuation mark reasonably close to the cap reads far better than that.
        String text = "Школа по подготовке выпускников к поступлению в зарубежные вузы ищет "
            + "менеджера по продажам! На рынке образования мы уже 5 лет и наши студенты с 95% успехом поступают.";
        String result = TelegramPostParser.title(text);

        assertTrue(result.endsWith("!"), result);
        assertFalse(result.contains("..."), result);
        assertTrue(result.length() <= 150, result);
    }

    @Test
    void title_longLineNoSentenceBoundaryNearCap_fallsBackToEllipsis() {
        // An exclamation mark right at the very start (well before the halfway point of
        // the cap) must not chop a 150-char title down to a handful of characters.
        String text = "Эй!" + " слово".repeat(40);
        String result = TelegramPostParser.title(text);

        assertTrue(result.endsWith("..."), result);
        assertEquals(150, result.length());
    }

    // ── employer ──

    @Test
    void employer_labeledInText_wins() {
        assertEquals("ООО Ромашка",
            TelegramPostParser.employer("Компания: ООО Ромашка\nЗадачи: ...", "Оператор", "somechan"));
    }

    @Test
    void employer_capitalCyrillicLabel_matches() {
        // Regression: CASE_INSENSITIVE alone folds only US-ASCII in Java, so a label
        // starting with capital "К" — i.e. essentially every real one — silently missed
        // and fell back to "@channel". Found in production: 6 posts naming an employer
        // outright still stored the channel handle instead.
        assertEquals("Darksy", TelegramPostParser.employer("Компания: Darksy\nФормат: full-time", "SMM", "vacancysmm"));
        assertEquals("АКБФ", TelegramPostParser.employer("Компания: АКБФ\nОбязанности:", "Аналитик", "onlinevakansii"));
        assertEquals("Ромашка", TelegramPostParser.employer("Работодатель: Ромашка", "Оператор", "somechan"));
    }

    @Test
    void employer_labelWithoutColon_notTreatedAsEmployer() {
        // "Компания развивает собственные бренды..." is prose, not a label.
        assertEquals("@somechan",
            TelegramPostParser.employer("Компания развивает собственные бренды", "Оператор", "somechan"));
    }

    @Test
    void employer_labeledValueThatIsBulletList_rejected() {
        assertEquals("@somechan",
            TelegramPostParser.employer("Работодатель:\n— ставить задачи\n— вести отчёт", "Оператор", "somechan"));
    }

    @Test
    void employer_trailingCapitalizedRunInTitle_usedWhenTextHasNoLabel() {
        assertEquals("Emerging Travel Group",
            TelegramPostParser.employer("Описание без метки", "Брендинг-дизайнер в Emerging Travel Group", "somechan"));
    }

    @Test
    void employer_platformNameInTitle_notMistakenForCompany() {
        assertEquals("@somechan",
            TelegramPostParser.employer("Описание", "SMM-менеджер в Telegram", "somechan"));
    }

    @Test
    void employer_lowercasePhraseInTitle_notMistakenForCompany() {
        assertEquals("@somechan",
            TelegramPostParser.employer("Описание", "Дизайнер для долгосрочного сотрудничества", "somechan"));
    }

    @Test
    void employer_projectTypeInTitle_notMistakenForCompany() {
        // Live false positives: "MMA/UFC-проекта" and "FinTech-проект" got captured as
        // the "company" — a project type, not who's hiring. Neither starts with a
        // literal platform name, so PLATFORM_NOT_EMPLOYER alone didn't catch them.
        assertEquals("@somechan",
            TelegramPostParser.employer("Описание", "Reels-мейкер для MMA/UFC-проекта", "somechan"));
        assertEquals("@somechan",
            TelegramPostParser.employer("Описание", "Senior Technical Writer в FinTech-проект", "somechan"));
    }

    @Test
    void employer_contentFormatInTitle_notMistakenForCompany() {
        // "AI-креатор для Reels" — Reels is a content format (like Instagram/YouTube),
        // not an employer.
        assertEquals("@somechan", TelegramPostParser.employer("Описание", "AI-креатор для Reels", "somechan"));
    }

    @Test
    void employer_titleTrailingEmployer_stripsSentenceFinalPeriod() {
        // "...для SP Candle." — the sentence's own final period was dragged into the
        // capture along with the real company name.
        assertEquals("SP Candle",
            TelegramPostParser.employer("Описание", "Ищу ассистента для SP Candle.", "somechan"));
    }

    @Test
    void employer_labeledEmployer_stripsSentenceFinalPeriod() {
        assertEquals("ООО Ромашка",
            TelegramPostParser.employer("Компания: ООО Ромашка.\nЗадачи: ...", "Оператор", "somechan"));
    }

    // ── finder.work-style "COMPANY • Без опыта" teaser (noexperience/theyseeku/distantsiya) ──

    @Test
    void employer_structuredCompanyBulletNoExperience_extracted() {
        String text = "Специалист по рефинансированию\nот 100 000 ₽\nПКБ • Без опыта\n\nhttps://finder.work/vacancies/1";
        assertEquals("ПКБ", TelegramPostParser.employer(text, "Специалист по рефинансированию", "noexperience"));
    }

    @Test
    void employer_structuredCompanyBulletExperienceYears_extracted() {
        String text = "Бухгалтер\nот 80 000 ₽\nРога и Копыта • От 3 лет\n\nhttps://finder.work/vacancies/2";
        assertEquals("Рога и Копыта", TelegramPostParser.employer(text, "Бухгалтер", "theyseeku"));
    }

    @Test
    void employer_bulletListItemNotExperienceLevel_notMistakenForCompany() {
        // Same channels also use "•" for ordinary bullet lists elsewhere in the post body
        // ("Мы предлагаем • удалённую работу") — only a bullet whose right side is
        // actually an experience-level phrase should be treated as the company line.
        String text = "Ассистент руководителя\nМы предлагаем:\nЧто предлагаем • удалённую работу\nГрафик • гибкий";
        assertEquals("@distantsiya", TelegramPostParser.employer(text, "Ассистент руководителя", "distantsiya"));
    }

    // ── salary ──

    @Test
    void salary_labeledRange_parsedWithSpacesStripped() {
        TelegramPostParser.Salary s = TelegramPostParser.salary("Зарплата: 80 000 - 120 000 руб.");
        assertNotNull(s);
        assertEquals(80000, s.from());
        assertEquals(120000, s.to());
        assertEquals("RUR", s.currency());
    }

    @Test
    void salary_bareLineNearTop_parsed() {
        TelegramPostParser.Salary s = TelegramPostParser.salary("Оператор чата\n60 000 – 250 000 ₽\nОписание");
        assertNotNull(s);
        assertEquals(60000, s.from());
        assertEquals(250000, s.to());
    }

    @Test
    void salary_bareLineDeepInPost_ignored() {
        // Same shape further down is far more likely a boost-price footer than a salary.
        String text = "Заголовок\nстрока\nстрока\nстрока\nстрока\n5 000 ₽";
        assertNull(TelegramPostParser.salary(text));
    }

    @Test
    void salary_notStated_returnsNull() {
        assertNull(TelegramPostParser.salary("Оператор чата\nОбязанности: отвечать в чате"));
    }

    @Test
    void salary_foreignCurrency_normalized() {
        assertEquals("USD", TelegramPostParser.salary("Оплата: 3 000 USD").currency());
    }

    // ── hh.ru link (Path A vs Path B) ──

    @Test
    void hhLink_bareLinkWithoutScheme_normalized() {
        TelegramPostParser.HhLink link = TelegramPostParser.hhLink("Подробности: ufa.hh.ru/vacancy/123456789");
        assertNotNull(link);
        assertEquals("https://ufa.hh.ru/vacancy/123456789", link.url());
        assertEquals("123456789", link.hhId());
    }

    @Test
    void hhLink_absent_returnsNull() {
        assertNull(TelegramPostParser.hhLink("Пишите в лс @somebody"));
    }

    @Test
    void hhLink_noCitySubdomain_stillMatched() {
        // Observed live: "Контакты: https://hh.ru/vacancy/136211253" (hh.ru's own generic
        // domain, no city subdomain) fell through to Path B — a bare teaser — instead of
        // Path A's full scrape, because the old pattern required a subdomain prefix.
        TelegramPostParser.HhLink link = TelegramPostParser.hhLink("Контакты: https://hh.ru/vacancy/136211253");
        assertNotNull(link);
        assertEquals("136211253", link.hhId());
    }

    @Test
    void hhLink_lookalikeDomainContainingHhRuAsSubstring_notMatched() {
        // Regression guard for making the subdomain optional above: "myhh.ru" must not
        // be mistaken for hh.ru just because "hh.ru" appears as a substring of it.
        assertNull(TelegramPostParser.hhLink("Подробности на myhh.ru/vacancy/999"));
    }

    // ── channel from hh_id ──

    @Test
    void channelFromHhId_underscoresInChannelName_parsedWhole() {
        assertEquals("rabota_is_doma_vakansii",
            TelegramPostParser.channelFromHhId("tg_rabota_is_doma_vakansii_9712"));
    }

    @Test
    void channelFromHhId_plainHhId_returnsNull() {
        assertNull(TelegramPostParser.channelFromHhId("136268015"));
        assertNull(TelegramPostParser.channelFromHhId(null));
    }

    // ── apply destination ──

    @Test
    void isSelfLink_recognizesPostsOwnLink_butNotAChannelLink() {
        assertTrue(TelegramPostParser.isSelfLink("https://t.me/freelancce/15611"));
        assertFalse(TelegramPostParser.isSelfLink("https://t.me/freelancce"));
        assertFalse(TelegramPostParser.isSelfLink("https://hh.ru/vacancy/1"));
        assertFalse(TelegramPostParser.isSelfLink(null));
    }

    @Test
    void contact_emailPreferredFirst() {
        assertEquals("sasha@fond-igra.ru",
            TelegramPostParser.contact("Отклик:\n sasha@fond-igra.ru").display());
    }

    @Test
    void contact_phoneWhenNoEmail() {
        TelegramPostParser.Contact c = TelegramPostParser.contact("Звоните: +7 495 123 45 67");
        assertNotNull(c);
        assertEquals("📞", c.emoji());
    }

    @Test
    void contact_personalUsernameOnlyNextToKeyword() {
        assertEquals("https://t.me/azatka_kzn",
            TelegramPostParser.contact("Для отклика напиши Ассистент в лс @azatka_kzn").display());
        // A bare mention crediting a repost source is not the reader's contact.
        assertNull(TelegramPostParser.contact("Репост из @somechannel, вакансия интересная."));
    }

    @Test
    void externalUrl_prefersNonTelegramLink() {
        assertEquals("https://kadrout.ru/vacancies/42",
            TelegramPostParser.externalUrl("Посмотреть полностью https://kadrout.ru/vacancies/42"));
        assertNull(TelegramPostParser.externalUrl("Только ссылка на пост https://t.me/chan/1"));
    }

    @Test
    void externalUrl_trailingSentencePunctuationStripped() {
        assertEquals("https://example.com/job",
            TelegramPostParser.externalUrl("Подробности тут: https://example.com/job."));
    }
}
