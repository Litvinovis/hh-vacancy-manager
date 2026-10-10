package com.hh.gui.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Слова-исключения поиска: совпадение целым словом с окончанием, а не подстрокой.
 *
 * Список приходит из excluded_text ссылки hh (см. SearchService.excludeWordsFromUrl), а hh
 * отсекает по словам с морфологией: «агент» у него — «агент», «агенты», «агентов», но не
 * «агентство». Подстрока резала больше, чем hh: на 500 карточках выдачи 10.10.2026 отсеяла
 * «Кадровое агентство HR-TARGET», «Трафик-агентство СОДА», «Коллегию адвокатов» (по «колл»)
 * и «турагента» — ни одной из них hh не исключал. Окончания — грубое приближение морфологии:
 * основа плюс короткое падежное окончание, поэтому «агентство» и «колледж» не совпадают,
 * а «колл-центр», «грузчики», «риэлторов» — совпадают.
 */
public final class ExcludeWords {

    private ExcludeWords() {}

    private static final String ENDINGS =
        "(?:а|я|ы|и|у|ю|е|о|ь|ом|ем|ём|ой|ей|ою|ею|ов|ев|ёв|ам|ям|ами|ями|ах|ях"
            + "|ый|ий|ая|яя|ое|ее|ые|ие|ого|его|ому|ему|ым|им|ых|их|ую|юю|s|es)?";

    /** Готовые шаблоны для списка слов; фраза из нескольких слов совпадает подряд идущими словами. */
    public static List<Pattern> compile(List<String> words) {
        if (words == null) return List.of();
        return words.stream()
            .filter(w -> w != null && !w.isBlank())
            .map(ExcludeWords::pattern)
            .toList();
    }

    /** Есть ли в тексте хоть одно слово-исключение. */
    public static boolean matchesAny(String text, List<Pattern> patterns) {
        if (text == null || text.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static Pattern pattern(String phrase) {
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])");
        String[] parts = phrase.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("[\\s\\-]+");
            sb.append(Pattern.quote(parts[i])).append(ENDINGS);
        }
        sb.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
