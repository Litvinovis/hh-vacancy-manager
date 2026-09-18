package com.hh.gui.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стоп-слова из ссылки hh: hh отсекает их по своим полям, приложение — по названию и
 * работодателю, поэтому списки должны совпадать. Раньше это делалось копипастой.
 */
class SearchServiceExcludeWordsTest {

    @Test
    void parsesExcludedTextFromRealSearchUrl() {
        String url = "https://ufa.hh.ru/search/vacancy?area=113&work_format=REMOTE"
            + "&excluded_text=%D0%BA%D0%BE%D0%BB%D0%BB%2C+call%2C+%D0%B3%D1%80%D1%83%D0%B7%D1%87%D0%B8%D0%BA"
            + "&label=with_salary";

        assertEquals(List.of("колл", "call", "грузчик"), SearchService.excludeWordsFromUrl(url));
    }

    @Test
    void urlWithoutExcludedText_returnsEmpty() {
        assertTrue(SearchService.excludeWordsFromUrl(
            "https://hh.ru/search/vacancy?area=113&work_format=REMOTE").isEmpty());
    }

    @Test
    void blankOrBrokenUrl_returnsEmptyInsteadOfThrowing() {
        assertTrue(SearchService.excludeWordsFromUrl(null).isEmpty());
        assertTrue(SearchService.excludeWordsFromUrl("").isEmpty());
        assertTrue(SearchService.excludeWordsFromUrl("не ссылка вовсе").isEmpty());
    }
}
