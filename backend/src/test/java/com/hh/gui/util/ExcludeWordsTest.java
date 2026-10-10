package com.hh.gui.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcludeWordsTest {

    private static boolean excluded(String text, String... words) {
        List<Pattern> patterns = ExcludeWords.compile(List.of(words));
        return ExcludeWords.matchesAny(text, patterns);
    }

    @Test
    void wordWithCaseEndings_matches() {
        assertTrue(excluded("Агент по недвижимости", "агент"));
        assertTrue(excluded("Ищем агентов в команду", "агент"));
        assertTrue(excluded("Грузчики на склад", "грузчик"));
        assertTrue(excluded("Набор риэлторов", "риэлтор"));
        assertTrue(excluded("Оператор колл-центра", "колл"));
        assertTrue(excluded("Call-центр, входящая линия", "call"));
        assertTrue(excluded("Outbound calls", "call"));
    }

    @Test
    void differentWordStartingWithTheSameLetters_doesNotMatch() {
        // Живые карточки 10.10.2026, которые подстрока отсекала, а hh — нет
        assertFalse(excluded("Кадровое агентство HR-TARGET", "агент"));
        assertFalse(excluded("Трафик - агентство СОДА", "агент"));
        assertFalse(excluded("Коллегия адвокатов г. Москвы", "колл"));
        assertFalse(excluded("Менеджер по туризму/турагент", "агент"));
        assertFalse(excluded("Преподаватель колледжа", "колл"));
        assertFalse(excluded("Настройка callback-форм", "call"));
    }

    @Test
    void phrase_matchesConsecutiveWords_caseInsensitive() {
        assertTrue(excluded("ООО АГЕНТСТВО НЕДВИЖИМОСТИ Инфинити", "агентство недвижимости"));
        assertFalse(excluded("Агентство цифрового маркетинга, недвижимость не продаём", "агентство недвижимости"));
    }

    @Test
    void emptyInput_neverMatches() {
        assertFalse(ExcludeWords.matchesAny(null, ExcludeWords.compile(List.of("агент"))));
        assertFalse(ExcludeWords.matchesAny("Агент", ExcludeWords.compile(null)));
        assertFalse(ExcludeWords.matchesAny("Агент", ExcludeWords.compile(List.of(" "))));
    }
}
