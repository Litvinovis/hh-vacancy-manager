package com.hh.gui.util;

import com.hh.gui.model.Vacancy;

/**
 * Renders a vacancy as a plain-text VK wall post. Unlike Telegram (parse_mode=HTML, see
 * VacancyPostFormatter), a regular VK community wall post has no markup at all — bold/
 * links only exist on VK's own "статья"/donut posts, not plain wall.post text — so this
 * mirrors publicPost()'s content with the HTML stripped out, relying on VK's own
 * automatic linkification of a bare URL in plain text instead of an anchor tag.
 *
 * Shares field prep (title/company/salary/reason truncation, the "@channel placeholder
 * isn't a real company" fallback) with VacancyPostFormatter via its package-private
 * {@code fields()} so the two destinations' content can't quietly drift apart.
 */
public final class VkPostFormatter {

    private VkPostFormatter() {}

    public static String publicPost(Vacancy v) {
        VacancyPostFormatter.Fields f = VacancyPostFormatter.fields(v);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📌 %s\n", f.title()));
        sb.append(String.format("🏢 %s · 💰 %s\n", f.company(), f.salary()));
        if (f.reason() != null && !f.reason().isBlank()) {
            sb.append(String.format("💡 %s\n", f.reason()));
        }
        String noveltyEmoji = v.getNoveltyColor() != null ? VacancyPostFormatter.NOVELTY_EMOJI.get(v.getNoveltyColor()) : null;
        if (noveltyEmoji != null && v.getNoveltyNote() != null && !v.getNoveltyNote().isBlank()) {
            sb.append(String.format("%s %s\n", noveltyEmoji, VacancyPostFormatter.capitalize(v.getNoveltyNote())));
        }
        sb.append(applyLine(v));
        return sb.toString();
    }

    /** Same self-link/contact-fallback logic as VacancyPostFormatter's applyLine, minus
     *  the HTML anchor — VK linkifies a bare URL in plain text on its own. */
    private static String applyLine(Vacancy v) {
        String url = v.getUrl();
        if (TelegramPostParser.isSelfLink(url)) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            return contact != null ? String.format("%s %s\n", contact.emoji(), contact.display()) : "";
        }
        if (url == null || url.isBlank()) return "";
        return String.format("👉 Откликнуться: %s\n", url);
    }
}
