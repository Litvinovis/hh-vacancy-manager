package com.hh.gui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiTextTest {

    @Test
    void latinLookalikeInsideRussianWord_isFixed() {
        // Пост 1317 в канале, 26.09.2026: «свободa» с латинской a
        assertEquals("Творческая видеоработа, свобода подачи", AiText.clean("Творческая видеоработа, свободa подачи"));
    }

    @Test
    void chineseCharacters_rejectWholeText() {
        // Пост 1312: «Аналитика и数字-управление на площадках»
        assertNull(AiText.clean("Аналитика и数字-управление на площадках"));
    }

    @Test
    void wordFromTwoAlphabetsThatCannotBeFixed_rejectsText() {
        // Пост 1304: «Rutинные задачи»
        assertNull(AiText.clean("Rutинные задачи, но с ответственностью"));
    }

    @Test
    void normalMixOfRussianAndEnglishWords_untouched() {
        String s = "SEO, A/B-тесты, работа с Ozon и Wildberries, IT-компания, 1С";
        assertEquals(s, AiText.clean(s));
    }

    @Test
    void blankInput_returnedAsIs() {
        assertNull(AiText.clean(null));
        assertEquals("", AiText.clean(""));
    }
}
