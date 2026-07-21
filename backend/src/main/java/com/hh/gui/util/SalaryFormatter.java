package com.hh.gui.util;

import com.hh.gui.model.Vacancy;

/** Formats a vacancy's salary range as human-readable Russian text. */
public final class SalaryFormatter {

    private SalaryFormatter() {}

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

    private static String range(Vacancy v) {
        boolean hasFrom = v.getSalaryFrom() != null && v.getSalaryFrom() > 0;
        boolean hasTo = v.getSalaryTo() != null && v.getSalaryTo() > 0;
        if (!hasFrom && !hasTo) return null;
        StringBuilder sb = new StringBuilder();
        if (hasFrom) sb.append("от ").append(v.getSalaryFrom());
        if (hasTo) sb.append(" до ").append(v.getSalaryTo());
        if (v.getCurrency() != null) sb.append(" ").append(v.getCurrency());
        return sb.toString();
    }
}
