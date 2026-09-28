package com.hh.gui.content;

import com.hh.gui.model.Vacancy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Обзоры «удалённая работа по профессии» — отдельный поток статей под поиск ВК (28.09.2026).
 *
 * Обычные статьи ({@link ContentTopics}) пишутся для подписчиков, а эти — для тех, кто ещё
 * не подписан и ищет работу запросом вида «удаленная работа ассистентом» или «менеджер
 * wildberries удаленно вакансии». Поэтому первая строка повторяет такой запрос, текст
 * держится на свежих вакансиях этой профессии из нашей базы, а в конце код дописывает
 * призыв вступить в сообщество.
 *
 * key — стабильный topic_key в vk_articles (по нему антиповтор); менять нельзя.
 */
public final class SeoTopics {
    private SeoTopics() {}

    public static final String KIND = "seo";

    /**
     * @param headline  первая строка поста — формулировка поискового запроса
     * @param keywords  слова, которые люди вводят в поиск: модель должна естественно их употребить
     * @param hashtag   тег профессии (без «ё», как пишут в поиске)
     * @param titleMatch корни в названии вакансии, по которым она относится к профессии
     * @param descriptionMatch корни в описании (только для «без опыта»), null — не смотреть
     */
    public record Topic(String key, String headline, String keywords, String hashtag,
                        Pattern titleMatch, Pattern descriptionMatch) {

        public boolean matches(Vacancy v) {
            if (titleMatch != null && v.getTitle() != null
                && titleMatch.matcher(v.getTitle().toLowerCase(Locale.ROOT)).find()) return true;
            return descriptionMatch != null && v.getDescription() != null
                && descriptionMatch.matcher(v.getDescription().toLowerCase(Locale.ROOT)).find();
        }
    }

    /** \\b должен понимать кириллицу: без UNICODE_CHARACTER_CLASS «\\bвб\\b» не находит ничего. */
    private static Pattern re(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static Topic t(String key, String headline, String keywords, String hashtag, String titleRegex) {
        return new Topic(key, headline, keywords, hashtag, re(titleRegex), null);
    }

    public static final List<Topic> ALL = List.of(
        t("seo_assistant", "Удалённая работа ассистентом руководителя: какие вакансии есть сейчас",
            "удаленная работа ассистентом, помощник руководителя удаленно, личный ассистент вакансии",
            "#ассистент", "ассистент|помощни|секретар|right hand"),
        t("seo_marketplace", "Менеджер маркетплейсов на удалёнке: вакансии Wildberries и Ozon",
            "менеджер маркетплейсов удаленно, работа на wildberries из дома, менеджер ozon вакансии",
            "#маркетплейсы", "маркетплейс|wildberries|ozon|озон|\\bwb\\b|\\bвб\\b|яндекс маркет"),
        t("seo_support", "Работа оператором чата и поддержки из дома: что предлагают",
            "работа оператором чата на дому, удаленная работа в поддержке, модератор удаленно",
            "#поддержка", "поддержк|оператор|модерат|служб[аы] заботы|support"),
        t("seo_smm", "SMM и контент на удалёнке: вакансии для контент-менеджеров и креаторов",
            "smm менеджер удаленно, контент-менеджер вакансии, работа копирайтером на дому, ugc креатор",
            "#smm", "smm|смм|контент|копирайт|креатор|ugc|рилс|reels|редактор"),
        t("seo_accountant", "Бухгалтер на удалёнке: вакансии и зарплаты",
            "удаленная работа бухгалтером, бухгалтер на дому вакансии, бухгалтер удаленно",
            "#бухгалтер", "бухгалтер"),
        t("seo_hr", "HR и рекрутер на удалёнке: вакансии для специалистов по подбору",
            "рекрутер удаленно, hr менеджер удаленная работа, специалист по подбору персонала на дому",
            "#hr", "\\bhr\\b|рекрут|подбор|кадр"),
        t("seo_designer", "Дизайнер на удалёнке: вакансии для графических и веб-дизайнеров",
            "дизайнер удаленно вакансии, работа графическим дизайнером на дому, дизайнер карточек для маркетплейсов",
            "#дизайнер", "дизайн"),
        t("seo_sales", "Менеджер по продажам на удалёнке: вакансии без холодных звонков и с ними",
            "удаленная работа менеджером по продажам, продажи из дома, менеджер по работе с клиентами удаленно",
            "#продажи", "продаж|по работе с клиент|аккаунт"),
        new Topic("seo_no_experience", "Удалённая работа без опыта: вакансии, куда берут новичков",
            "удаленная работа без опыта, работа на дому для новичков, подработка онлайн без опыта",
            "#безопыта", null, re("без опыта|опыт не (обязателен|нужен|требуется)|обучим|всему научим"))
    );

    public static Topic byKey(String key) {
        return ALL.stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
    }
}
