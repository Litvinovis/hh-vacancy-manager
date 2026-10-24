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
        return foldToCyrillic(word);
    }

    /** Обратное направление: кириллические двойники — в латиницу (у→y, е→e, о→o, ...).
     *  Нужно enum-полям ответа модели (verdict/noveltyColor), чьи допустимые значения
     *  латинские: «yеs» с кириллической «е» чинится только так. */
    private static final Map<Character, Character> CYRILLIC_TO_LATIN = Map.ofEntries(
        Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
        Map.entry('с', 'c'), Map.entry('х', 'x'), Map.entry('у', 'y'), Map.entry('к', 'k'));

    /** Все латинские двойники — в кириллицу. Публично: нормализация verdict/noveltyColor в
     *  VacancyAiAnalyzer прогоняет значение через это и таблицу синонимов («да»→yes), а у
     *  синонимов ключи кириллические. */
    public static String foldToCyrillic(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) sb.append(HOMOGLYPHS.getOrDefault(ch, ch));
        return sb.toString();
    }

    /** Все кириллические двойники — в латиницу (см. CYRILLIC_TO_LATIN). */
    public static String foldToLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) sb.append(CYRILLIC_TO_LATIN.getOrDefault(ch, ch));
        return sb.toString();
    }
}
