package com.hh.gui.util;

import com.hh.gui.model.Vacancy;

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
        String range = range(v);
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
        boolean hasFrom = v.getSalaryFrom() != null && v.getSalaryFrom() > 0;
        boolean hasTo = v.getSalaryTo() != null && v.getSalaryTo() > 0;
        if (!hasFrom && !hasTo) return null;
        StringBuilder sb = new StringBuilder();
        if (hasFrom) sb.append("от ").append(THOUSANDS.format(v.getSalaryFrom()));
        if (hasTo) sb.append(" до ").append(THOUSANDS.format(v.getSalaryTo()));
        if (v.getCurrency() != null && !v.getCurrency().isBlank()) {
            sb.append(" ").append(CURRENCY_NAMES.getOrDefault(v.getCurrency(), v.getCurrency()));
        }
        return sb.toString();
    }
}
