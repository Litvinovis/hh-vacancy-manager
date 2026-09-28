package com.hh.gui.content;

import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeoTopicsTest {

    private static Vacancy v(String title, String description) {
        Vacancy v = new Vacancy(); v.setTitle(title); v.setDescription(description); return v;
    }

    @Test
    void marketplace_matchesWbAsWholeWordOnly() {
        SeoTopics.Topic t = SeoTopics.byKey("seo_marketplace");
        assertTrue(t.matches(v("Ассистент менеджера ВБ", "")));
        assertTrue(t.matches(v("Менеджер Ozon", "")));
        assertFalse(t.matches(v("Вбивание данных в таблицы", "")), "«вб» внутри слова — не Wildberries");
    }

    @Test
    void noExperience_looksAtDescription() {
        SeoTopics.Topic t = SeoTopics.byKey("seo_no_experience");
        assertTrue(t.matches(v("Оператор", "Опыт не обязателен, всему научим")));
        assertFalse(t.matches(v("Без опыта", "Нужен опыт от 3 лет")), "название не смотрим — только описание");
    }

    @Test
    void keysAreUnique() {
        assertEquals(SeoTopics.ALL.size(), SeoTopics.ALL.stream().map(SeoTopics.Topic::key).distinct().count());
    }
}
