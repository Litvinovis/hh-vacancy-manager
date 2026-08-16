package com.hh.gui.util;

import com.hh.gui.model.Vacancy;

import java.util.Map;

/**
 * Renders a vacancy as Telegram-ready HTML, in the two shapes this app sends:
 * a {@link #publicPost} for the public channel and subscriber feed, and a
 * {@link #reportEntry} line-item for the personal digest.
 *
 * The two used to be separate methods in VacancyPipelineService that prepared the
 * same four fields with the same magic limits, and they had already drifted: the
 * fix for the dead "link back to the post you're reading" destination landed only
 * in the public one, leaving the personal report to print a self-link forever. That
 * asymmetry is exactly what a shared field-prep step prevents, so both shapes now
 * derive from the same {@link Fields} and the same {@link #applyLine}.
 */
public final class VacancyPostFormatter {

    private VacancyPostFormatter() {}

    // Title/reason are scraped or AI-generated text with no hard length cap upstream —
    // truncated defensively so one unusually long entry can't alone blow past Telegram's
    // 4096-char message limit, regardless of how entries get grouped into a message.
    private static final int MAX_TITLE_CHARS = 150;
    private static final int MAX_REASON_CHARS = 300;

    private static final Map<String, String> NOVELTY_EMOJI = Map.of("red", "🔴", "yellow", "🟡", "green", "🟢");

    /** The four fields both shapes need, prepared identically for both. */
    private record Fields(String title, String company, String salary, String reason) {}

    private static Fields fields(Vacancy v) {
        // A leading "@" means employer() (see TelegramPostParser) never found a real
        // employer and fell back to the source channel's own handle — verified live
        // that showing that handle as the "company" reads as a bug to a reader (an
        // @-handle isn't something you can apply to), so it's treated the same as no
        // company at all rather than printed as if it meant something.
        String company = v.getCompany();
        boolean hasRealCompany = company != null && !company.isEmpty() && !company.startsWith("@");
        return new Fields(
            truncate(v.getTitle(), MAX_TITLE_CHARS),
            hasRealCompany ? escapeHtml(company) : "компания не указана",
            SalaryFormatter.forReport(v),
            truncate(v.getAiReason(), MAX_REASON_CHARS));
    }

    /** Public channel / subscriber post: no internal scoring, just what a reader needs. */
    public static String publicPost(Vacancy v) {
        Fields f = fields(v);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📌 <b>%s</b>\n", escapeHtml(f.title())));
        sb.append(String.format("🏢 %s · 💰 %s\n", f.company(), f.salary()));
        if (f.reason() != null && !f.reason().isBlank()) {
            sb.append(String.format("💡 %s\n", escapeHtml(f.reason())));
        }
        String noveltyEmoji = v.getNoveltyColor() != null ? NOVELTY_EMOJI.get(v.getNoveltyColor()) : null;
        if (noveltyEmoji != null && v.getNoveltyNote() != null && !v.getNoveltyNote().isBlank()) {
            sb.append(String.format("%s %s\n", noveltyEmoji, escapeHtml(capitalize(v.getNoveltyNote()))));
        }
        sb.append(applyLine(v, "👉", ""));
        return sb.toString();
    }

    /** One entry of the personal digest: carries the AI score, which public posts never expose. */
    public static String reportEntry(Vacancy v) {
        Fields f = fields(v);
        int score = v.getAiScore() != null ? v.getAiScore() : 0;
        String emoji = score >= 80 ? "🟢" : score >= 60 ? "🟡" : "🟠";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>[%d%%]</b> %s\n", emoji, score, escapeHtml(f.title())));
        sb.append(String.format("   🏢 %s | 💰 %s\n", f.company(), f.salary()));
        sb.append(String.format("   💡 %s\n", escapeHtml(f.reason())));
        sb.append(applyLine(v, "🔗", "   "));
        sb.append("\n");
        return sb.toString();
    }

    /**
     * The "where to apply" line. When the stored url is nothing but a link to the
     * Telegram post itself — a dead end, since the reader is already looking at that
     * post — falls back to an email/phone/personal contact pulled out of the description
     * (see TelegramPostParser.contact). Path A and externally-linked posts keep their
     * real destination and never reach the fallback.
     *
     * @param urlEmoji marker used when the destination is a plain URL; a contact brings its own.
     * @param indent   leading whitespace, since the personal digest indents its detail lines.
     */
    private static String applyLine(Vacancy v, String urlEmoji, String indent) {
        String url = v.getUrl();
        if (TelegramPostParser.isSelfLink(url)) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            if (contact != null) {
                // A t.me link (💬) is a raw URL exactly like the plain case below —
                // wrap it the same way. Email/phone (📧/📞) are already short and
                // self-explanatory, and Telegram auto-linkifies them from plain text
                // on its own, so those are left as-is.
                String shown = "💬".equals(contact.emoji())
                    ? String.format("<a href=\"%s\">Откликнуться</a>", escapeHtml(contact.display()))
                    : contact.display();
                return String.format("%s%s %s\n", indent, contact.emoji(), shown);
            }
            // Self-link with no extractable contact either — found live (18% of sent
            // Telegram-sourced posts): falling through below would print the exact
            // dead self-link this check exists to avoid, right back as "Откликнуться".
            // Genuinely nothing worth pointing the reader to, same as a blank url.
            return "";
        }
        if (url == null || url.isBlank()) return "";
        // Verified live (kadrout.ru): percent-encoded Cyrillic slugs push some URLs past
        // 300 characters — printed as plain text, that's a wall of "%D0%9A..." inline
        // instead of a clean line. HTML parse_mode is already in use for this message
        // (see TelegramNotifier), so the ugly part becomes the anchor's href instead of
        // the visible text.
        return String.format("%s%s <a href=\"%s\">Откликнуться</a>\n", indent, urlEmoji, escapeHtml(url));
    }

    public static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
