package com.hh.gui.util;

/**
 * Builds the vacancy dedup key: normalized "title|employer".
 *
 * The same real vacancy posted separately per city gets a different hh_id each
 * time — this key is what lets the pipeline recognize such clones and reuse the
 * scraped content / AI verdict instead of paying for each copy again. City names
 * deliberately aren't stripped out: they rarely appear in the title itself, and
 * this is a best-effort heuristic, not a guaranteed match.
 */
public final class DedupKeys {

    private DedupKeys() {}

    /**
     * Returns "" (no key, no dedup) unless BOTH title and employer are present —
     * an employer-less key like "менеджер|" would happily cross-match completely
     * unrelated companies' postings that share a generic title.
     */
    public static String compute(String title, String employerName) {
        String t = normalize(title);
        String e = normalize(employerName);
        if (t.isEmpty() || e.isEmpty()) return "";
        return t + "|" + e;
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
            .replaceAll("[^a-zа-яё0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
