package com.hh.gui.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls structured vacancy fields out of the free-form text of a Telegram channel
 * post — title, employer, salary, an "apply here" destination — for posts that are
 * themselves the only source (Path B in VacancyPipelineService.discoverFromTelegram),
 * plus recognition of first-party hh.ru links that route a post down Path A instead.
 *
 * Every rule here was derived from live channel output, not from a spec: channels
 * format posts however their author or bot felt like, so each pattern below carries
 * the concrete case that forced it. Deliberately conservative throughout — a wrong
 * salary or a wrong employer actively misleads a reader in a way "not specified"
 * never does, so a pattern that isn't confident returns nothing rather than a guess.
 *
 * Pure functions, no state and no collaborators (same shape as DedupKeys /
 * SalaryFormatter in this package) — extracted out of VacancyPipelineService, which
 * had accumulated 15 regexes and 11 parsing methods inside a class that also
 * orchestrates scraping, AI analysis and publishing.
 */
public final class TelegramPostParser {

    private TelegramPostParser() {}

    private static final int MAX_TITLE_CHARS = 150;

    /** A first-party hh.ru vacancy link found in a post, with the scheme normalized. */
    public record HhLink(String url, String hhId) {}

    /** Salary parsed out of post text; any component may be null when not stated. */
    public record Salary(Integer from, Integer to, String currency) {}

    /** How a reader should apply — {@code emoji} labels the channel, {@code display} is the address itself. */
    public record Contact(String emoji, String display) {}

    private static final Pattern HH_LINK_PATTERN =
        Pattern.compile("(?:https?://)?[a-z0-9-]+\\.hh\\.ru/vacancy/(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * The hh.ru vacancy this post links to, or null if it doesn't link to one — the
     * Path A / Path B decision. Callers get the URL scheme-normalized because posts
     * commonly write the link bare ("spb.hh.ru/vacancy/123"), and a stored URL without
     * a scheme is not clickable downstream.
     */
    public static HhLink hhLink(String text) {
        if (text == null) return null;
        Matcher m = HH_LINK_PATTERN.matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        if (!url.matches("(?i)https?://.*")) url = "https://" + url;
        return new HhLink(url, m.group(1));
    }

    // Best-effort employer extraction for Telegram posts with no first-party job-board
    // link — mirrors the legacy collector/tg_parser.py heuristic. Channel posts rarely
    // label the field explicitly; when they don't, the channel itself becomes the
    // "employer" for dedup purposes (see below).
    // The colon is required (not just optional whitespace): verified live that without
    // it, this matched ordinary sentences that merely contain the word "компания" —
    // e.g. "Компания развивает собственные бренды..." — and captured whatever followed
    // as the "employer", nonsense unrelated to who's actually hiring.
    // "организация" dropped from the keyword list: verified live that some posts use it
    // as a DUTIES sub-heading ("Обязанности: ... Организация:\n— ставить задачи...") —
    // meaning "organizing work", not "the hiring organization" — and the colon-required
    // rule above doesn't disambiguate that usage from a real "Организация: ООО Ромашка"
    // label, so this word is inherently unsafe as a label keyword here.
    // UNICODE_CASE is required, not optional: CASE_INSENSITIVE on its own folds only
    // US-ASCII in Java, so "Компания:" with the capital Cyrillic К that starts almost
    // every real label line never matched — verified against the production DB, where
    // 6 posts spelled out "Компания: Darksy" / "Компания: АКБФ" and every one of them
    // still fell back to "@channel", while zero ever matched via a lowercase spelling.
    // This pattern had effectively never fired in production before the flag was added.
    private static final Pattern TG_EMPLOYER_PATTERN =
        Pattern.compile("(?:компания|фирма|работодатель)\\s*:\\s*([^\\n,]{2,60})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // A labeled value that's actually a bullet-list start ("Организация:\n— ставить
    // задачи...") begins with a list marker on the captured text — reject those even
    // for the three keywords kept above, as a general safety net.
    private static final Pattern LIST_MARKER_START = Pattern.compile("^[\\s]*[-—•*]");

    // Verified live: most titles that DO name an employer follow "Роль в/для
    // КомпанияName" ("Брендинг-дизайнер в Emerging Travel Group", "SMM Manager в
    // GipsyTeam") — a trailing capitalized run after "в"/"для" is a much better bet
    // than the raw "@channel" fallback, which is what readers used to see as the
    // "employer" for every Path B post regardless of whether the title clearly named one.
    // Requiring a capital first letter is what keeps this from misfiring on ordinary
    // lowercase phrases ("для международных проектов", "для долгосрочного сотрудничества").
    private static final Pattern TITLE_TRAILING_EMPLOYER =
        Pattern.compile("(?:\\sв|\\sдля)\\s+([A-ZА-ЯЁ][\\w\\-&+./]*(?:\\s[A-ZА-ЯЁ0-9][\\w\\-&+./]*)*)\\s*$",
            Pattern.UNICODE_CASE);

    // "Роль в Telegram" / "для YouTube-проекта" name the PLATFORM the work happens on,
    // not who's hiring — TITLE_TRAILING_EMPLOYER can't distinguish that from a real
    // company name syntactically, so reject a capture that starts with one of these.
    private static final Pattern PLATFORM_NOT_EMPLOYER = Pattern.compile(
        "^(?:Telegram|Instagram|YouTube|TikTok|VK|WhatsApp|Facebook|Zoom|LinkedIn)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Who's hiring, falling back to {@code "@" + channel} when the post never says —
     * never null, because no dedup key can be computed without SOME employer value
     * (see DedupKeys). That fallback keeps same-channel reposts catchable even though
     * it can't catch the same posting cross-channel.
     */
    public static String employer(String text, String title, String channel) {
        Matcher m = TG_EMPLOYER_PATTERN.matcher(text);
        if (m.find() && !LIST_MARKER_START.matcher(m.group(1)).find()) {
            return m.group(1).trim();
        }
        Matcher titleMatch = TITLE_TRAILING_EMPLOYER.matcher(title);
        if (titleMatch.find() && !PLATFORM_NOT_EMPLOYER.matcher(titleMatch.group(1)).find()) {
            return titleMatch.group(1).trim();
        }
        return "@" + channel;
    }

    // Path B hh_id format is "tg_<channel>_<messageId>" (see tg-scraper/server.js's
    // `id: tg_${channel}_${realId}`) — channel usernames can themselves contain
    // underscores (e.g. "rabota_is_doma_vakansii"), so a naive split(_) would misparse;
    // greedy .+ backing off only enough to satisfy the trailing \d+$ anchor handles that
    // correctly.
    private static final Pattern TG_CHANNEL_FROM_HHID = Pattern.compile("^tg_(.+)_\\d+$");

    /**
     * The source channel encoded in a Path B hh_id, used to tag metrics by channel
     * without adding a DB column — null for Path A (real hh.ru numeric ids) and for
     * anything else hh.ru-sourced.
     */
    public static String channelFromHhId(String hhId) {
        if (hhId == null) return null;
        Matcher m = TG_CHANNEL_FROM_HHID.matcher(hhId);
        return m.matches() ? m.group(1) : null;
    }

    // Tier 1 (high confidence): an explicit label directly in front of the number(s) —
    // "Заработная плата от 40000 рублей", "Оплата: 80 000 рублей", "З/п 55000 RUR".
    // The label itself is strong enough evidence that a currency token isn't required.
    private static final Pattern LABELED_SALARY = Pattern.compile(
        "(?:заработная\\s+плата|зарплата|з/?п|оплата)\\s*:?\\s*(?:от\\s+)?" +
        "(\\d[\\d\\s]{2,8}\\d)(?:\\s*[-–—]\\s*(?:до\\s+)?(\\d[\\d\\s]{2,8}\\d))?" +
        "\\s*(₽|руб(?:лей|\\.)?|RUR|RUB|\\$|USD|€|EUR)?",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Tier 2 (position-restricted): a line that's ENTIRELY a number range + currency,
    // nothing else — the "60 000 – 250 000 ₽" line these bot-formatted posts put right
    // under the title (mirrors hh.ru's own salary-widget style). Only scanned within the
    // first few lines — the same bare pattern found deep in a post is far more likely to
    // be an unrelated number (a boost-price footer, a phone number) than a salary, so it
    // is deliberately NOT scanned across the whole text.
    private static final Pattern BARE_SALARY_LINE = Pattern.compile(
        "^(?:от\\s+)?(\\d[\\d\\s]{2,8}\\d)(?:\\s*[-–—]\\s*(?:до\\s+)?(\\d[\\d\\s]{2,8}\\d))?" +
        "\\s*(₽|руб(?:лей|\\.)?|RUR|RUB|\\$|USD|€|EUR)\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int SALARY_SCAN_LINES = 4;

    /** Salary if the post states one confidently enough (see the two tiers above), else null. */
    public static Salary salary(String text) {
        if (text == null) return null;
        Matcher labeled = LABELED_SALARY.matcher(text);
        if (labeled.find()) {
            Integer from = parseSalaryNumber(labeled.group(1));
            Integer to = parseSalaryNumber(labeled.group(2));
            if (from != null || to != null) {
                return new Salary(from, to, normalizeCurrency(labeled.group(3)));
            }
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < Math.min(lines.length, SALARY_SCAN_LINES); i++) {
            String line = stripArtifacts(lines[i]);
            Matcher bare = BARE_SALARY_LINE.matcher(line);
            if (bare.matches()) {
                Integer from = parseSalaryNumber(bare.group(1));
                Integer to = parseSalaryNumber(bare.group(2));
                if (from != null || to != null) {
                    return new Salary(from, to, normalizeCurrency(bare.group(3)));
                }
            }
        }
        return null;
    }

    private static Integer parseSalaryNumber(String raw) {
        if (raw == null) return null;
        try {
            int n = Integer.parseInt(raw.replaceAll("\\s", ""));
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) return "RUR";
        String r = raw.toLowerCase();
        if (r.contains("$") || r.contains("usd")) return "USD";
        if (r.contains("€") || r.contains("eur")) return "EUR";
        return "RUR";
    }

    // Any http(s) link that ISN'T a t.me/telegram.me self-link — verified live on
    // kadrout: its bot posts a short teaser ending in "Посмотреть вакансию полностью"
    // linking to kadrout.ru/vacancies/... (the full, untruncated listing on the
    // aggregator's own site). Trailing punctuation is stripped since a URL at the end
    // of a sentence commonly picks up a period/comma/closing paren from the prose.
    private static final Pattern EXTERNAL_URL =
        Pattern.compile("https?://(?!t\\.me/|telegram\\.me/)\\S+", Pattern.CASE_INSENSITIVE);

    /** A non-Telegram link in the post — a far better "read more" destination than the post itself. */
    public static String externalUrl(String text) {
        if (text == null) return null;
        Matcher m = EXTERNAL_URL.matcher(text);
        if (!m.find()) return null;
        return m.group().replaceAll("[.,;:!?)\\]]+$", "");
    }

    private static final Pattern TG_SELF_LINK =
        Pattern.compile("^https?://t\\.me/[^/]+/\\d+$", Pattern.CASE_INSENSITIVE);

    /**
     * True when a URL is just a link to the Telegram post itself — a dead end for a
     * reader, who is already looking at that post. Path B vacancies fall back to it
     * when nothing better was found, which is why callers check before publishing.
     */
    public static boolean isSelfLink(String url) {
        return url != null && TG_SELF_LINK.matcher(url).matches();
    }

    // Fallback "apply here" contact for posts with neither a job-board link nor any
    // other external URL — verified live (freelancce channel, "Отклик:
    // sasha@fond-igra.ru") that the published post's "👉" line otherwise fell back to
    // the Telegram post's OWN link, pointing a reader back at the post they're already
    // reading. Tried in order: email is unambiguous on its own; a full 11-digit RU
    // phone number's format is distinctive enough to need no keyword either; a personal
    // @username is common enough as an unrelated mention (the channel's own handle,
    // other posts' employers) that it's only trusted next to an actual "как
    // откликнуться" style keyword on the same line.
    private static final Pattern CONTACT_EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CONTACT_PHONE =
        Pattern.compile("(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}(?!\\d)");
    private static final Pattern CONTACT_PERSONAL_USERNAME = Pattern.compile(
        "(?:напиши(?:те)?|пишите|отклик|контакт|обращайтесь|связаться|для\\s+связи)\\S*[:\\s].*?(@[a-zA-Z0-9_]{5,})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Where a reader should actually apply, or null if the post never says. */
    public static Contact contact(String text) {
        if (text == null) return null;
        Matcher email = CONTACT_EMAIL.matcher(text);
        if (email.find()) return new Contact("📧", email.group());
        Matcher phone = CONTACT_PHONE.matcher(text);
        if (phone.find()) return new Contact("📞", phone.group().trim());
        Matcher personal = CONTACT_PERSONAL_USERNAME.matcher(text);
        if (personal.find()) return new Contact("💬", "https://t.me/" + personal.group(1).substring(1));
        return null;
    }

    // Verified live: many channels (e.g. frilanser_vacansii) open every post with a
    // hashtag line (#вакансия #smm #удаленно) before the actual role name on the next
    // non-empty line — without skipping it, every such vacancy's title was literally
    // its hashtags, not a job title.
    private static final Pattern HASHTAG_ONLY_LINE = Pattern.compile("^(?:#\\S+\\s*)+$");
    // Some channels format the title line as a markdown heading with a generic
    // "Вакансия:" prefix ("### Вакансия: Асессор") — strip both so the title is just
    // the role name, matching how every other channel's plain-text title line reads.
    // CASE_INSENSITIVE alone only folds US-ASCII case in Java — Cyrillic needs
    // UNICODE_CASE too, or "Вакансия" (capital В) silently fails to match "вакансия"
    // and only the "###" heading gets stripped, leaving the prefix behind.
    private static final Pattern MD_HEADING_AND_VACANCY_PREFIX =
        Pattern.compile("^#{1,6}\\s*(?:вакансия\\s*:?\\s*)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Telegram's rich-text editor prepends a zero-width space to some posts (styling
     * artifact, not visible in the app) — String.strip() doesn't treat it as whitespace,
     * so left alone it silently survives into the title/description.
     */
    private static String stripArtifacts(String s) {
        return s.replace("​", "").strip();
    }

    /** The role name: first line that isn't hashtags or a heading prefix, capped at {@value #MAX_TITLE_CHARS} chars. */
    public static String title(String text) {
        for (String line : text.split("\n")) {
            String t = stripArtifacts(line);
            if (t.isEmpty() || HASHTAG_ONLY_LINE.matcher(t).matches()) continue;
            t = MD_HEADING_AND_VACANCY_PREFIX.matcher(t).replaceFirst("").strip();
            if (t.isEmpty()) continue;
            return truncateTitle(t);
        }
        return truncateTitle(text);
    }

    private static String truncateTitle(String s) {
        return s.length() > MAX_TITLE_CHARS ? s.substring(0, MAX_TITLE_CHARS - 3) + "..." : s;
    }
}
