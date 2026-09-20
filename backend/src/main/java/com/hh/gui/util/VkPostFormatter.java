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
        if (!"компания не указана".equals(f.company())) {
            sb.append(f.company()).append("\n");
        }
        sb.append("\n");
        if (f.reason() != null && !f.reason().isBlank()) {
            sb.append("Что делать: ").append(VacancyPostFormatter.capitalize(f.reason())).append("\n");
        }
        if (v.getNoveltyNote() != null && !v.getNoveltyNote().isBlank()) {
            sb.append("Почему интересно: ").append(VacancyPostFormatter.capitalize(v.getNoveltyNote())).append("\n");
        }
        sb.append("\n");
        sb.append(applyHint(v)).append("\n\n");
        sb.append(String.join(" ", hashtags(v, communityScreenName)));
        return sb.toString();
    }

    /** Название — зарплата, удалённо. Зарплата известна не всегда; тогда просто «удалённо». */
    static String hook(VacancyPostFormatter.Fields f) {
        String salary = f.salary();
        boolean hasSalary = salary != null && !salary.isBlank() && !salary.contains("не указана");
        return hasSalary ? f.title() + " — " + salary + ", удалённо" : f.title() + " — удалённо";
    }

    /** Строка в посте, куда смотреть за откликом. Ссылка сама — в комментарии. */
    private static String applyHint(Vacancy v) {
        if (TelegramPostParser.isSelfLink(v.getUrl())) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            if (contact != null) return "Как откликнуться: " + contact.display();
        }
        return "Как откликнуться — в первом комментарии 👇";
    }

    /**
     * Текст первого комментария под постом: ссылка на отклик. Для вакансий из Telegram
     * без реального url — контакт из описания, если он есть; иначе null (комментарий не нужен).
     */
    public static String applyComment(Vacancy v) {
        String url = v.getUrl();
        if (TelegramPostParser.isSelfLink(url)) {
            TelegramPostParser.Contact contact = TelegramPostParser.contact(v.getDescription());
            return contact != null ? "Откликнуться: " + contact.display() : null;
        }
        if (url == null || url.isBlank()) return null;
        return "Откликнуться: " + url;
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
    static String kindTag(String title) {
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
                if (t.contains(rule[i])) return rule[rule.length - 1];
            }
        }
        return null;
    }
}
