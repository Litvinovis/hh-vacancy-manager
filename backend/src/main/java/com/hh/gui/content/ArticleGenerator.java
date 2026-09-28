package com.hh.gui.content;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.repository.VkArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Пишет текст статьи по теме из плана. Для тем «на данных» сначала собирает факты из базы
 * (причины fraud-вердиктов, топ профессий, зарплаты, воронка) и отдаёт их модели как
 * материал — иначе она выдумает цифры, а именно реальные цифры и есть наше отличие.
 *
 * Требования к тексту заданы в промпте и повторяют план продвижения: хук первой строкой,
 * живой язык, конкретика, без стен эмодзи и без внешних ссылок, вопрос читателю в конце
 * (повод для комментария) и ровно два хэштега.
 */
@Service
public class ArticleGenerator {

    private static final Logger log = LoggerFactory.getLogger(ArticleGenerator.class);
    private static final int MAX_TOKENS = 1800;

    private final VkArticleRepository articles;
    private final VacancyRepository vacancies;
    private final VacancyAiAnalyzer analyzer;

    public ArticleGenerator(VkArticleRepository articles, VacancyRepository vacancies, VacancyAiAnalyzer analyzer) {
        this.articles = articles;
        this.vacancies = vacancies;
        this.analyzer = analyzer;
    }

    /** Сгенерировать тексты для всех запланированных на дату и раньше. Возвращает число готовых. */
    public int generateDue(String upToDate) {
        int done = 0;
        for (VkArticle a : articles.findToGenerate(upToDate)) {
            if (a.isPoll()) {
                // Опросу текст не нужен — вопрос и варианты уже в записи
                a.setStatus("generated");
                a.setGeneratedAt(Instant.now().toString());
                articles.update(a);
                done++;
                continue;
            }
            if (SeoTopics.KIND.equals(a.getKind())) {
                if (generateSeo(a)) done++;
                continue;
            }
            ContentTopics.Topic topic = ContentTopics.byKey(a.getTopicKey());
            if (topic == null) {
                log.warn("Статья id={}: тема «{}» не найдена в каталоге — помечена failed", a.getId(), a.getTopicKey());
                a.setStatus("failed");
                articles.update(a);
                continue;
            }
            String text = analyzer.generateText(prompt(topic), MAX_TOKENS);
            if (text == null || text.length() < 200) {
                // Не failed: следующий тик попробует снова, а провал модели на одной статье не
                // должен вычёркивать тему из плана
                log.warn("Статья id={} «{}»: модель не вернула текст, попробуем позже", a.getId(), topic.title());
                continue;
            }
            a.setBody(text);
            a.setStatus("generated");
            a.setGeneratedAt(Instant.now().toString());
            articles.update(a);
            log.info("Статья id={} «{}» сгенерирована ({} символов)", a.getId(), topic.title(), text.length());
            done++;
        }
        return done;
    }

    String prompt(ContentTopics.Topic topic) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты ведёшь сообщество ВКонтакте «Интересная удалёнка» — подборку удалённых вакансий без сложных ")
          .append("технических требований (ассистенты, поддержка, контент, координация). Напиши пост для сообщества.\n\n");
        sb.append("ТЕМА: ").append(topic.title()).append("\n");
        sb.append("О ЧЁМ: ").append(topic.brief()).append("\n\n");
        if (topic.dataBacked()) {
            sb.append("ФАКТЫ ИЗ НАШЕЙ БАЗЫ (используй только их, ничего не выдумывай, цифры не округляй в большую сторону):\n")
              .append(facts(topic.key())).append("\n\n");
        }
        sb.append("ТРЕБОВАНИЯ К ТЕКСТУ:\n")
          .append("- 700–1100 символов, обычный текст без разметки.\n")
          .append("- Первая строка — цепляющая, по делу, без кликбейта; она видна в ленте до «Показать полностью».\n")
          .append("- Живой разговорный язык, короткие абзацы, конкретика вместо общих слов.\n")
          .append("- Никаких списков из эмодзи, максимум один эмодзи на весь текст или ни одного.\n")
          .append("- Без внешних ссылок и без упоминания других площадок.\n")
          .append("- В конце — один вопрос читателю, на который хочется ответить в комментариях.\n")
          .append("- Последней строкой ровно два хэштега: #удалённаяработа и один по теме.\n")
          .append("- Только текст поста, без заголовка «Тема:», без пояснений от себя.\n");
        return sb.toString();
    }

    /** Материал из базы для тем на данных. Формат — строки «факт: значение», модели так проще. */
    String facts(String topicKey) {
        try {
            switch (topicKey) {
                case "fraud_signs" -> {
                    List<String> reasons = vacancies.recentFraudReasons(12);
                    return reasons.isEmpty() ? "мошеннических вакансий за период не было"
                        : "причины, по которым модель отметила вакансии как обман:\n- " + String.join("\n- ", reasons);
                }
                case "top_professions_week" -> {
                    Map<String, Integer> top = vacancies.topTitlesSince(daysAgo(7), 10);
                    StringBuilder sb = new StringBuilder("самые частые названия вакансий за 7 дней (название: сколько раз):\n");
                    top.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                    return sb.toString();
                }
                case "salary_snapshot" -> {
                    Map<String, Integer> med = vacancies.medianSalaryByKind(daysAgo(30));
                    StringBuilder sb = new StringBuilder("медианная зарплата «от» по типам работ за 30 дней, рубли:\n");
                    med.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                    return sb.toString();
                }
                case "week_digest", "rejected_reasons" -> {
                    // Для статьи планка канала не важна: «одобрено» — это всё одобренное.
                    Map<String, Integer> f = vacancies.funnelSnapshot(0);
                    Map<String, Integer> verdicts = vacancies.verdictCountsSince(daysAgo(7));
                    List<String> reasons = vacancies.recentRejectReasons(10);
                    return "воронка сейчас (этап: количество): " + f + "\n"
                        + "вердикты за 7 дней (вердикт: количество): " + verdicts + "\n"
                        + "типичные причины отказа:\n- " + String.join("\n- ", reasons);
                }
                default -> { return "данных нет"; }
            }
        } catch (Exception e) {
            log.warn("Не удалось собрать факты для темы {}: {}", topicKey, e.getMessage());
            return "данных нет";
        }
    }

    // ── обзоры по профессиям (SeoTopics) ──

    /** Меньше — обзор не пишем: по трём вакансиям это не обзор, а подборка. */
    static final int SEO_MIN_VACANCIES = 5;
    static final int SEO_WINDOW_DAYS = 14;

    /**
     * Призыв вступить пишет код, а не модель: так он есть в каждом обзоре и без искажений.
     * Хэштеги — без «ё»: в поиске пишут «удаленная», а не «удалённая».
     */
    static final String SEO_CALL_TO_ACTION =
        "Каждый день публикуем проверенные удалённые вакансии без скама и сомнительных схем — " +
        "вступайте в «Интересную удалёнку», чтобы не пропустить новые.";

    private boolean generateSeo(VkArticle a) {
        SeoTopics.Topic topic = SeoTopics.byKey(a.getTopicKey());
        if (topic == null) {
            log.warn("Обзор id={}: тема «{}» не найдена в каталоге — помечен failed", a.getId(), a.getTopicKey());
            a.setStatus("failed");
            articles.update(a);
            return false;
        }
        List<com.hh.gui.model.Vacancy> matched = vacancies.approvedSince(daysAgo(SEO_WINDOW_DAYS), 3000).stream()
            .filter(topic::matches).toList();
        if (matched.size() < SEO_MIN_VACANCIES) {
            log.info("Обзор «{}»: за {} дней всего {} вакансий — пропущен", topic.key(), SEO_WINDOW_DAYS, matched.size());
            a.setStatus("failed");
            articles.update(a);
            return false;
        }
        String text = analyzer.generateText(seoPrompt(topic, seoFacts(matched)), MAX_TOKENS);
        if (text == null || text.length() < 300) {
            log.warn("Обзор id={} «{}»: модель не вернула текст, попробуем позже", a.getId(), topic.key());
            return false;
        }
        text = com.hh.gui.util.AiText.clean(text);
        if (text == null) {
            // Испорченный ответ: не публикуем, следующий тик попросит модель заново
            log.warn("Обзор id={} «{}»: модель вернула испорченный текст, попробуем позже", a.getId(), topic.key());
            return false;
        }
        a.setBody(seoPost(text, topic));
        a.setStatus("generated");
        a.setGeneratedAt(Instant.now().toString());
        articles.update(a);
        log.info("Обзор id={} «{}» сгенерирован по {} вакансиям", a.getId(), topic.key(), matched.size());
        return true;
    }

    String seoPrompt(SeoTopics.Topic topic, String facts) {
        return "Ты ведёшь сообщество ВКонтакте «Интересная удалёнка» с проверенными удалёнными вакансиями. "
            + "Напиши пост-обзор, который найдут через поиск ВК люди, ищущие такую работу.\n\n"
            + "ПЕРВАЯ СТРОКА ПОСТА (дословно): " + topic.headline() + "\n"
            + "ПОИСКОВЫЕ ФРАЗЫ (употреби 2–3 из них естественно, в разных абзацах, без перечисления подряд): "
            + topic.keywords() + "\n\n"
            + "ФАКТЫ ИЗ НАШЕЙ БАЗЫ ЗА " + SEO_WINDOW_DAYS + " ДНЕЙ (используй только их, ничего не выдумывай):\n"
            + facts + "\n\n"
            + "ТРЕБОВАНИЯ:\n"
            + "- 900–1400 символов, обычный текст без разметки, короткие абзацы.\n"
            + "- Что за работа, чем занимаются по этим вакансиям, какие зарплаты, кого ищут — по фактам.\n"
            + "- Названия компаний из фактов можно упомянуть как примеры; ФИО людей не упоминай.\n"
            + "- Без внешних ссылок, без эмодзи-списков (максимум один эмодзи), без хэштегов.\n"
            + "- Последний абзац — один вопрос читателю для комментариев.\n"
            + "- Только текст поста, без пояснений от себя.\n";
    }

    /** Материал для обзора: сколько вакансий, зарплаты, задачи, примеры работодателей. */
    String seoFacts(List<com.hh.gui.model.Vacancy> matched) {
        StringBuilder sb = new StringBuilder();
        sb.append("вакансий по этой профессии: ").append(matched.size()).append("\n");
        List<Integer> salaries = matched.stream()
            .filter(v -> v.getSalaryFrom() != null && v.getSalaryFrom() > 0)
            .filter(v -> v.getCurrency() == null || v.getCurrency().equals("RUR") || v.getCurrency().equals("RUB"))
            .map(com.hh.gui.model.Vacancy::getSalaryFrom).sorted().toList();
        if (salaries.size() >= 3) {
            sb.append("зарплата «от», рубли: минимум ").append(salaries.get(0))
              .append(", медиана ").append(salaries.get(salaries.size() / 2))
              .append(", максимум ").append(salaries.get(salaries.size() - 1))
              .append(" (по ").append(salaries.size()).append(" вакансиям с указанной зарплатой)\n");
        }
        sb.append("примеры вакансий (название — работодатель — зарплата):\n");
        matched.stream().limit(6).forEach(v -> sb.append("- ").append(v.getTitle()).append(" — ")
            .append(employerForFacts(v)).append(" — ")
            .append(com.hh.gui.util.SalaryFormatter.forReport(v)).append("\n"));
        sb.append("чем предстоит заниматься (кратко по вакансиям):\n");
        matched.stream().map(v -> com.hh.gui.util.AiText.clean(v.getAiReason()))
            .filter(r -> r != null && !r.isBlank()).distinct().limit(10)
            .forEach(r -> sb.append("- ").append(r).append("\n"));
        return sb.toString();
    }

    /** ИП и физлиц в текст не несём — это ФИО конкретных людей, а не бренд. */
    private static String employerForFacts(com.hh.gui.model.Vacancy v) {
        String c = v.getCompany();
        if (c == null || c.isBlank() || c.startsWith("@")) return "компания не указана";
        if (c.matches("(?U)^\\p{Lu}\\p{Ll}+ \\p{Lu}\\p{Ll}+ \\p{Lu}\\p{Ll}+(вич|вна|ична|ич)$") || c.contains("ИП ")) {
            return "частный работодатель";
        }
        return c;
    }

    /** Текст модели + призыв вступить + хэштеги; хэштеги модели срезаются, чтобы не было дублей. */
    static String seoPost(String modelText, SeoTopics.Topic topic) {
        String body = modelText.strip().replaceAll("(?m)^\\s*(#\\S+\\s*)+$", "").strip();
        return body + "\n\n" + SEO_CALL_TO_ACTION + "\n\n"
            + "#удаленнаяработа #работанадому " + topic.hashtag();
    }

    private static String daysAgo(int days) {
        return Instant.now().minusSeconds(days * 86400L).toString();
    }
}
