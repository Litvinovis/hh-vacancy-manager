package com.hh.gui.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка коротких текстов от бесплатных моделей перед публикацией. Живые случаи 25–27.09.2026
 * (dots-3-note-preview) ушли в канал как есть: «Rutинные задачи», «Аналитика и数字-управление»,
 * «свободa» с латинской a. Похожие латинские буквы внутри русского слова чиним, а текст с
 * иероглифами или словом из двух алфавитов, которое не чинится, выбрасываем целиком —
 * строка 💡/🟢 необязательная, пост без неё лучше поста с мусором.
 */
public final class AiText {

    private AiText() {}

    // Латиница, которая выглядит как кириллица, — модель путает их внутри слова.
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
        Map.entry('a', 'а'), Map.entry('e', 'е'), Map.entry('o', 'о'), Map.entry('p', 'р'),
        Map.entry('c', 'с'), Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('k', 'к'),
        Map.entry('A', 'А'), Map.entry('E', 'Е'), Map.entry('O', 'О'), Map.entry('P', 'Р'),
        Map.entry('C', 'С'), Map.entry('X', 'Х'), Map.entry('H', 'Н'), Map.entry('K', 'К'),
        Map.entry('M', 'М'), Map.entry('T', 'Т'), Map.entry('B', 'В'));

    /** Иероглифы и хангыль: в русскоязычном тексте про вакансии их не бывает вовсе. */
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
    /** Сплошная последовательность букв — «IT-компания» и «SEO/ASO» дают по два отдельных слова. */
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");

    /** Текст, пригодный к публикации, или null, если он испорчен. Пустой вход возвращается как есть. */
    public static String clean(String text) {
        if (text == null || text.isBlank()) return text;
        if (CJK.matcher(text).find()) return null;
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = WORD.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String word = m.group();
            if (isMixed(word)) {
                word = fixHomoglyphs(word);
                if (isMixed(word)) return null;
            }
            out.append(word);
            last = m.end();
        }
        return out.append(text.substring(last)).toString();
    }

    private static boolean isMixed(String word) {
        return CYRILLIC.matcher(word).find() && LATIN.matcher(word).find();
    }

    private static String fixHomoglyphs(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (char ch : word.toCharArray()) sb.append(HOMOGLYPHS.getOrDefault(ch, ch));
        return sb.toString();
    }
}
