package com.hh.gui.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Line-level fuzzy match for near-duplicate vacancy descriptions that DedupKeys'
 * exact hash misses — e.g. two postings identical except one line ("2/2, 5/2;" vs
 * "2/2 полный день, 5/2;"). Jaccard similarity over normalized, non-blank lines:
 * a single differing line among twenty still scores ~0.9, a genuinely different
 * job (different duties/requirements throughout) scores far lower.
 */
public final class TextSimilarity {

    private TextSimilarity() {}

    /** @return 0..1 — 1.0 for identical (or both-empty) line sets, 0.0 for no overlap at all. */
    public static double lineSimilarity(String a, String b) {
        Set<String> linesA = normalizedLines(a);
        Set<String> linesB = normalizedLines(b);
        if (linesA.isEmpty() && linesB.isEmpty()) return 1.0;
        if (linesA.isEmpty() || linesB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(linesA);
        intersection.retainAll(linesB);
        Set<String> union = new HashSet<>(linesA);
        union.addAll(linesB);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> normalizedLines(String text) {
        if (text == null) return Set.of();
        Set<String> lines = new HashSet<>();
        for (String line : text.split("\n")) {
            String normalized = DedupKeys.normalize(line);
            if (!normalized.isEmpty()) lines.add(normalized);
        }
        return lines;
    }
}
