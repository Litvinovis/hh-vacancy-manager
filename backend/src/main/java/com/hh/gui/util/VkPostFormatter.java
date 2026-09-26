package com.hh.gui.util;

import com.hh.gui.model.Vacancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Пост вакансии для стены ВКонтакте — plain text, у обычного wall.post разметки нет.
 *
 * Переработан 20.09.2026 под то, что реально двигает пост в умной ленте ВК, а не под
 * копию Telegram-формата (она давала голый текст с четырьмя эмодзи-маркерами и внешней
 * ссылкой в каждом посте — по разборам 2026 это худшее сочетание для охвата):
 *
 * <ul>
 *   <li>Первая строка — хук: название, зарплата, «удалённо». В ленте до «Показать
 *       полностью» видна только она, и именно по ней решают, раскрывать ли пост.</li>
 *   <li>Ссылки на отклик в тексте нет — она уходит в первый комментарий
 *       ({@link #applyComment}): внешняя ссылка в посте режет охват.</li>
 *   <li>Ровно 2–3 хэштега: общий, по типу работы и сообщественный #вакансии@имя —
 *       по нему ищут внутри сообщества. Больше трёх в ВК не работают, больше десяти —
 *       выкидывают из поиска.</li>
 *   <li>Эмодзи — минимум: перегруз эмодзи и одинаковый шаблон умная лента считает
 *       «повторяющимся форматом».</li>
 * </ul>
 *
 * Подготовка полей (обрезка, заглушка «компания не указана») общая с Telegram-форматтером
 * через package-private {@code fields()}, чтобы содержание не расходилось.
 */
public final class VkPostFormatter {
    private VkPostFormatter() {}

    public static String publicPost(Vacancy v) {
        return publicPost(v, null);
    }

    /**
     * @param communityScreenName короткое имя сообщества для #вакансии@имя; null — без
     *                            сообщественного тега (когда имя не удалось получить)
     */
    public static String publicPost(Vacancy v, String communityScreenName) {
        VacancyPostFormatter.Fields f = VacancyPostFormatter.fields(v);
        StringBuilder sb = new StringBuilder();

        sb.append(hook(f)).append("\n");
        // Не f.company(): поля общие с Telegram и там уже HTML-экранированы, а стена VK —
        // plain text, и «Джони & Клайд» выходил в пост как «Джони &amp; Клайд».
        if (VacancyPostFormatter.hasRealCompany(v)) {
            sb.append(v.getCompany().trim()).append("\n");
        }
        sb.append("\n");
        if (f.reason() != null && !f.reason().isBlank()) {
            sb.append("Что делать: ").append(VacancyPostFormatter.capitalize(f.reason())).append("\n");
        }
        if (v.getNoveltyNote() != null && !v.getNoveltyNote().isBlank()) {
            sb.append("Почему интересно: ").append(VacancyPostFormatter.capitalize(v.getNoveltyNote())).append("\n");
        }
        sb.append("\n");
        String hint = applyHint(v);
        if (hint != null) sb.append(hint).append("\n\n");
        sb.append(String.join(" ", hashtags(v, communityScreenName)));
        return sb.toString();
    }

    /** Название — зарплата, удалённо. Зарплата известна не всегда; тогда просто «удалённо». */
    static String hook(VacancyPostFormatter.Fields f) {
        String salary = f.salary();
        boolean hasSalary = salary != null && !salary.isBlank() && !salary.contains("не указана");
        String title = cleanTitle(f.title());
        return hasSalary ? title + " — " + salary + ", удалённо" : title + " — удалённо";
    }

    /**
     * Название из Telegram-поста часто начинается с эмодзи-маркера, а от него при разборе
     * остаётся невидимый селектор варианта (U+FE0F) — в ленте это выглядит как отступ
     * перед первой буквой. Срезаем всё, что до первой буквы или цифры.
     */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String cleaned = title.replaceFirst("^[^\\p{L}\\p{N}]+", "").trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    /** Ссылка на вакансию hh без трекинга выдачи (?hhtmFrom=…): короче и не выдаёт, откуда перешли. */
    static String cleanUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = HH_VACANCY.matcher(url);
        return m.find() ? "https://hh.ru/vacancy/" + m.group(1) : url;
    }

    private static final java.util.regex.Pattern HH_VACANCY =
        java.util.regex.Pattern.compile("^https?://(?:[\\w-]+\\.)?hh\\.ru/vacancy/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Строка в посте, куда смотреть за откликом. Ссылка сама — в комментарии. */
    private static String applyHint(Vacancy v) {
        if (TelegramPostParser.isDeadEndLink(v.getUrl())) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            if (contact != null) return "Как откликнуться: " + contact.display();
            // Ни ссылки, ни контакта — комментария не будет (applyComment вернёт null),
            // и обещание «в первом комментарии» отправляло бы читателя в пустоту.
            return null;
        }
        return "Как откликнуться — в первом комментарии 👇";
    }

    /**
     * Текст первого комментария под постом: ссылка на отклик. Для вакансий из Telegram
     * без реального url — контакт из описания, если он есть; иначе null (комментарий не нужен).
     */
    public static String applyComment(Vacancy v) {
        String url = v.getUrl();
        if (TelegramPostParser.isDeadEndLink(url)) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            return contact != null ? "Откликнуться: " + contact.display() : null;
        }
        if (url == null || url.isBlank()) return null;
        return "Откликнуться: " + cleanUrl(url);
    }

    /** Больше вакансий в подборке не бывает: пост длиннее 10 пунктов в ленте не дочитывают. */
    public static final int MAX_DIGEST_ITEMS = 10;
    /** Сколько символов «чем заниматься» на вакансию в подборке — одна строка на экране телефона. */
    private static final int DIGEST_REASON_CHARS = 90;

    /**
     * Подборка: несколько вакансий одним постом. Нужна, когда одобренных больше, чем окон
     * публикации (см. VkPublishQueue): стена одиночных постов режет охват, а подборка — один
     * пост с понятным хуком «N вакансий», который сохраняют и репостят.
     *
     * Устроена как одиночный пост: хук в первой строке, ссылок в тексте нет — они в первом
     * комментарии под теми же номерами ({@link #digestComment}), 2–3 хэштега.
     *
     * @param localHour час по поясу окна — для «утренней/дневной/вечерней» подборки
     */
    public static String digestPost(List<Vacancy> vacancies, String communityScreenName, int localHour) {
        List<Vacancy> items = vacancies.size() > MAX_DIGEST_ITEMS ? vacancies.subList(0, MAX_DIGEST_ITEMS) : vacancies;
        StringBuilder sb = new StringBuilder();
        sb.append(digestHook(items.size(), localHour)).append("\n\n");
        for (int i = 0; i < items.size(); i++) {
            Vacancy v = items.get(i);
            VacancyPostFormatter.Fields f = VacancyPostFormatter.fields(v);
            sb.append(i + 1).append(") ").append(hook(f)).append("\n");
            List<String> details = new ArrayList<>();
            if (VacancyPostFormatter.hasRealCompany(v)) details.add(v.getCompany().trim());
            if (f.reason() != null && !f.reason().isBlank()) {
                details.add(truncateAtWord(VacancyPostFormatter.capitalize(f.reason().trim()), DIGEST_REASON_CHARS));
            }
            if (!details.isEmpty()) sb.append("   ").append(String.join(" · ", details)).append("\n");
            sb.append("\n");
        }
        sb.append("Как откликнуться — в первом комментарии, по номеру вакансии 👇\n\n");
        sb.append(String.join(" ", digestHashtags(items, communityScreenName)));
        return sb.toString();
    }

    /**
     * Обрезка по границе слова: посимвольная давала в ленте «известный работо…» и «Вилка 60–10…».
     * Слово длиннее лимита (без пробелов) режется как раньше.
     */
    static String truncateAtWord(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) return s;
        String window = s.substring(0, maxChars);
        int space = window.lastIndexOf(' ');
        String cut = space > maxChars / 2 ? window.substring(0, space) : window;
        return cut.replaceAll("[\\s,;:·–—-]+$", "") + "…";
    }

    /** «Вечерняя подборка: 6 удалённых вакансий» — число в хуке само по себе цепляет в ленте. */
    static String digestHook(int count, int localHour) {
        String part = localHour < 12 ? "Утренняя" : localHour < 17 ? "Дневная" : "Вечерняя";
        return part + " подборка: " + count + " " + plural(count, "удалённая вакансия", "удалённые вакансии", "удалённых вакансий");
    }

    /** Первый комментарий под подборкой: ссылки/контакты под теми же номерами, что в посте. */
    public static String digestComment(List<Vacancy> vacancies) {
        List<Vacancy> items = vacancies.size() > MAX_DIGEST_ITEMS ? vacancies.subList(0, MAX_DIGEST_ITEMS) : vacancies;
        StringBuilder sb = new StringBuilder("Откликнуться:\n");
        boolean any = false;
        for (int i = 0; i < items.size(); i++) {
            String target = applyTarget(items.get(i));
            if (target == null) continue;
            sb.append(i + 1).append(") ").append(target).append("\n");
            any = true;
        }
        return any ? sb.toString().trim() : null;
    }

    /**
     * Есть ли куда откликнуться. Без ссылки и контакта вакансия в VK бесполезна: в подборке
     * её номер пропадал из комментария (пост 1796: в комментарии 4 номера из 7).
     */
    public static boolean hasApplyTarget(Vacancy v) {
        return applyTarget(v) != null;
    }

    /** Ссылка на отклик или контакт из текста Telegram-поста; null — откликаться некуда. */
    private static String applyTarget(Vacancy v) {
        String url = v.getUrl();
        if (TelegramPostParser.isDeadEndLink(url)) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            if (contact != null) return contact.display();
            return TelegramPostParser.isSelfLink(url) ? url : null;   // пост хотя бы откроется; jpg — нет
        }
        return url == null || url.isBlank() ? null : cleanUrl(url);
    }

    /** Для подборки — общий тег, самый частый тег типа работы (если есть) и сообщественный. */
    static List<String> digestHashtags(List<Vacancy> vacancies, String communityScreenName) {
        List<String> tags = new ArrayList<>();
        tags.add("#удалённаяработа");
        java.util.Map<String, Integer> kinds = new java.util.LinkedHashMap<>();
        for (Vacancy v : vacancies) {
            String kind = kindTag(v.getTitle());
            if (kind != null) kinds.merge(kind, 1, Integer::sum);
        }
        kinds.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
            .filter(e -> e.getValue() >= 2)
            .ifPresent(e -> tags.add(e.getKey()));
        if (communityScreenName != null && !communityScreenName.isBlank()) {
            tags.add("#вакансии@" + communityScreenName);
        }
        return tags;
    }

    static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }

    /** #удалённаяработа + тег по типу работы (если распознан) + #вакансии@сообщество. */
    static List<String> hashtags(Vacancy v, String communityScreenName) {
        List<String> tags = new ArrayList<>();
        tags.add("#удалённаяработа");
        String kind = kindTag(v.getTitle());
        if (kind != null) tags.add(kind);
        if (communityScreenName != null && !communityScreenName.isBlank()) {
            tags.add("#вакансии@" + communityScreenName);
        }
        return tags;
    }

    /**
     * Тег по типу работы из названия. Грубая эвристика по ключевым словам — цель не
     * классифицировать точно, а дать читателю и поиску ВК одно понятное слово. Не
     * распознали — тега нет, третий «обо всём» хуже, чем никакого.
     */
    /**
     * Короткие латинские ключи (hr, seo, smm) — только целым словом: подстрокой «hr» находился
     * в любом «Chrome», «seo» — в «Seoul». Русские корни по-прежнему ищутся подстрокой: у
     * них окончания («дизайн-ер», «продаж-и»), и слово целиком они не совпадут.
     */
    private static boolean matchesKeyword(String text, String keyword) {
        if (!keyword.chars().allMatch(c -> c < 128)) return text.contains(keyword);
        return java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(keyword) + "(?![a-z])")
            .matcher(text).find();
    }

    public static String kindTag(String title) {
        if (title == null) return null;
        String t = title.toLowerCase(Locale.ROOT);
        String[][] rules = {
            {"ассистент", "помощник", "секретар", "#ассистент"},
            {"поддержк", "оператор чат", "чат", "support", "#поддержка"},
            {"smm", "смм", "контент", "копирайт", "редактор", "reels", "рилс", "#контент"},
            {"дизайн", "designer", "#дизайн"},
            {"маркетолог", "маркетинг", "таргет", "seo", "#маркетинг"},
            {"модератор", "модерац", "#модерация"},
            {"менеджер по продаж", "продаж", "#продажи"},
            {"маркетплейс", "wildberries", "ozon", "озон", "#маркетплейсы"},
            {"бухгалтер", "финанс", "#бухгалтерия"},
            {"юрист", "#юрист"},
            {"рекрутер", "hr", "подбор персонала", "#hr"},
            {"аналитик", "#аналитика"},
            {"преподават", "учител", "репетитор", "#обучение"},
        };
        for (String[] rule : rules) {
            for (int i = 0; i < rule.length - 1; i++) {
                if (matchesKeyword(t, rule[i])) return rule[rule.length - 1];
            }
        }
        return null;
    }
}
