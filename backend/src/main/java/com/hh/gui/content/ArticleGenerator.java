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

    private static String daysAgo(int days) {
        return Instant.now().minusSeconds(days * 86400L).toString();
    }
}
