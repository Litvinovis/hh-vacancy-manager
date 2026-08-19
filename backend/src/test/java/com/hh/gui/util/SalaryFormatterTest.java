package com.hh.gui.util;

import com.hh.gui.client.CurrencyRateService;
import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SalaryFormatterTest {

    /** Canned rates, no network — mirrors this codebase's FakeFreeModelUpdater-style overrides. */
    static class FakeRates extends CurrencyRateService {
        @Override
        public Double rubPerUnit(String currencyCode) {
            if ("RUR".equals(currencyCode) || "RUB".equals(currencyCode)) return 1.0;
            if ("USD".equals(currencyCode)) return 92.5;
            return null; // валюта не в фиде
        }
    }

    private Vacancy vacancy(Integer from, Integer to, String currency) {
        Vacancy v = new Vacancy();
        v.setSalaryFrom(from);
        v.setSalaryTo(to);
        v.setCurrency(currency);
        return v;
    }

    @Test
    void forPrompt_withRates_appendsRubEquivalent_forRange() {
        String result = SalaryFormatter.forPrompt(vacancy(2000, 3000, "USD"), new FakeRates());

        assertTrue(result.contains("от 2 000 до 3 000 Долларов"), result);
        assertTrue(result.contains("(≈ 185 000–277 500 ₽)"), result);
    }

    @Test
    void forPrompt_withRates_appendsRubEquivalent_forSingleBound() {
        String result = SalaryFormatter.forPrompt(vacancy(2000, null, "USD"), new FakeRates());

        assertTrue(result.contains("(≈ 185 000 ₽)"), result);
        assertFalse(result.contains("–"), "одна граница не должна рисовать диапазон в скобках");
    }

    @Test
    void forPrompt_withoutRatesService_doesNotAppendAnything() {
        String result = SalaryFormatter.forPrompt(vacancy(2000, 3000, "USD"), null);

        assertFalse(result.contains("≈"), "без сервиса курсов — как раньше, без конвертации");
    }

    @Test
    void forPrompt_currencyMissingFromFeed_doesNotAppendAnything() {
        String result = SalaryFormatter.forPrompt(vacancy(1000, null, "EUR"), new FakeRates());

        assertFalse(result.contains("≈"), "валюта отсутствует в кэше курсов — не должно быть пустого/сломанного (≈)");
    }

    @Test
    void forPrompt_rurCurrency_neverAppendsEquivalent() {
        String result = SalaryFormatter.forPrompt(vacancy(100000, null, "RUR"), new FakeRates());

        assertFalse(result.contains("≈"), "рубли не нужно конвертировать сами в себя");
    }

    @Test
    void forPrompt_noArgOverload_stillWorksWithoutConversion() {
        String result = SalaryFormatter.forPrompt(vacancy(2000, 3000, "USD"));

        assertTrue(result.contains("от 2 000 до 3 000 Долларов"), result);
        assertFalse(result.contains("≈"));
    }
}
