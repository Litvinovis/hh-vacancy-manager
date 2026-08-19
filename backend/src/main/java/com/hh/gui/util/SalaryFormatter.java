package com.hh.gui.util;

import com.hh.gui.model.Vacancy;
import com.hh.gui.client.CurrencyRateService;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/** Formats a vacancy's salary range as human-readable Russian text. */
public final class SalaryFormatter {

    private SalaryFormatter() {}

    private static final DecimalFormat THOUSANDS = new DecimalFormat("#,###",
        new DecimalFormatSymbols(Locale.ROOT) {{ setGroupingSeparator(' '); }});

    private static final Map<String, String> CURRENCY_NAMES = Map.of(
        "RUR", "Рублей", "RUB", "Рублей", "USD", "Долларов", "EUR", "Евро", "KZT", "Тенге", "BYN", "Белорусских рублей");

    /** For AI prompts: notes when the salary is gross (pre-tax), since that affects how a floor should be judged. */
    public static String forPrompt(Vacancy v) {
        return forPrompt(v, null);
    }

    /**
     * Same as {@link #forPrompt(Vacancy)}, plus a "(≈ N ₽)" annotation for non-RUB
     * figures when {@code rates} has a rate for the currency — the original figure is
     * never replaced, only annotated, since the CBR feed can lag real market/crypto
     * rates. The model otherwise has to judge a $2000 offer against a rouble salary
     * floor entirely from prompt text, with no arithmetic done for it — this feeds it
     * a real number instead. {@code rates} may be null (no service wired at this call
     * site) or the currency may be missing from the feed — either way, silently falls
     * back to the unconverted figure.
     */
    public static String forPrompt(Vacancy v, CurrencyRateService rates) {
        String range = range(v, rates);
        if (range == null) return "не указана";
        if (v.isSalaryGross()) range += " (до вычета налогов)";
        return range;
    }

    /** For Telegram reports: gross/net distinction isn't worth the space in a compact notification. */
    public static String forReport(Vacancy v) {
        String range = range(v);
        return range != null ? range : "з/п не указана";
    }

    public static boolean hasSalary(Vacancy v) {
        return (v.getSalaryFrom() != null && v.getSalaryFrom() > 0) || (v.getSalaryTo() != null && v.getSalaryTo() > 0);
    }

    private static String range(Vacancy v) {
        return range(v, null);
    }

    private static String range(Vacancy v, CurrencyRateService rates) {
        boolean hasFrom = v.getSalaryFrom() != null && v.getSalaryFrom() > 0;
        boolean hasTo = v.getSalaryTo() != null && v.getSalaryTo() > 0;
        if (!hasFrom && !hasTo) return null;
        StringBuilder sb = new StringBuilder();
        if (hasFrom) sb.append("от ").append(THOUSANDS.format(v.getSalaryFrom()));
        if (hasTo) sb.append(" до ").append(THOUSANDS.format(v.getSalaryTo()));
        String currency = v.getCurrency();
        if (currency != null && !currency.isBlank()) {
            sb.append(" ").append(CURRENCY_NAMES.getOrDefault(currency, currency));
            appendRubEquivalent(sb, v, rates, currency, hasFrom, hasTo);
        }
        return sb.toString();
    }

    private static void appendRubEquivalent(StringBuilder sb, Vacancy v, CurrencyRateService rates,
                                             String currency, boolean hasFrom, boolean hasTo) {
        if (rates == null || "RUR".equals(currency) || "RUB".equals(currency)) return;
        Double rate = rates.rubPerUnit(currency);
        if (rate == null) return;
        sb.append(" (≈ ");
        if (hasFrom) sb.append(THOUSANDS.format(Math.round(v.getSalaryFrom() * rate)));
        if (hasFrom && hasTo) sb.append("–");
        if (hasTo) sb.append(THOUSANDS.format(Math.round(v.getSalaryTo() * rate)));
        sb.append(" ₽)");
    }
}
