package com.hh.gui.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the vacancy dedup key that lets the pipeline recognize the same real
 * posting cross-published under different hh_ids (e.g. once per city) and reuse
 * the scraped content / AI verdict instead of paying for each copy again.
 *
 * The same real posting is sometimes republished under a genuinely different
 * TITLE too — observed live: "Оператор call-центра" (Тольятти) and "Специалист
 * клиентской поддержки" (Ярославль), same employer, byte-identical description.
 * A title-based key can never catch that, so once a description is available
 * (post-scrape), it — not the title — is what the key is built from; title is
 * only the fallback for RSS-discovery-time calls where no description exists yet.
 */
public final class DedupKeys {

    private DedupKeys() {}

    /** Pre-scrape (RSS discovery): no description available yet, title is all there is. */
    public static String compute(String title, String employerName) {
        return compute(title, employerName, null);
    }

    /**
     * @param description when non-blank, the key is employer + a hash of the
     *                     normalized description, not the title (see class javadoc).
     *                     Returns "" (no key, no dedup) unless an employer is present
     *                     — an employer-less key would cross-match unrelated companies'
     *                     postings that happen to share a generic title/description.
     */
    public static String compute(String title, String employerName, String description) {
        String e = normalize(employerName);
        if (e.isEmpty()) return "";
        if (description != null && !description.isBlank()) {
            return e + "|desc:" + sha256(normalize(description));
        }
        String t = normalize(title);
        if (t.isEmpty()) return "";
        return t + "|" + e;
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
            .replaceAll("[^a-zа-яё0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String sha256(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
