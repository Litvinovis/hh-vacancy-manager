package com.hh.gui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DedupKeysTest {

    @Test
    void compute_normalizesCasePunctuationAndWhitespace() {
        assertEquals("специалист клиентской поддержки|т банк",
            DedupKeys.compute("Специалист   клиентской поддержки!", "«Т-Банк»"));
    }

    @Test
    void compute_sameRealVacancyAcrossCities_sameKey() {
        // Клоны одной вакансии по городам различаются только hh_id и адресом —
        // название и работодатель совпадают, и ключ обязан совпасть.
        String a = DedupKeys.compute("Консультант по банковским продуктам", "Т-Банк");
        String b = DedupKeys.compute("Консультант по банковским продуктам", "Т-Банк");
        assertEquals(a, b);
        assertFalse(a.isEmpty());
    }

    @Test
    void compute_missingEmployer_returnsEmpty_noCrossEmployerFalseMatch() {
        // Ключ "менеджер|" сматчил бы вакансии совершенно разных компаний
        // с одинаковым типовым названием — без работодателя ключа нет вообще.
        assertEquals("", DedupKeys.compute("Менеджер", null));
        assertEquals("", DedupKeys.compute("Менеджер", "   "));
        assertEquals("", DedupKeys.compute(null, "Т-Банк"));
    }
}
