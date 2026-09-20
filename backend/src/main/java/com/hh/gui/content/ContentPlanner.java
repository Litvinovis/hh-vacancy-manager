package com.hh.gui.content;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.VkArticle;
import com.hh.gui.repository.VkArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Составляет контент-план на неделю для сообщества VK.
 *
 * Раз в неделю (первый тик после понедельника, идемпотентно) выбирает темы из
 * {@link ContentTopics}: сначала те, что на данных, — они уникальны и дают поводы для
 * репостов; потом общие. Тема не повторяется, пока не пройдёт vkTopicCooldownDays с
 * прошлой публикации — за этим и нужна база статей, иначе через месяц пойдут те же
 * тексты. Опросы планируются отдельным счётчиком.
 *
 * Дни публикации — из настройки (по умолчанию вт/чт/сб): между вакансиями, чтобы лента
 * сообщества не была сплошной подборкой.
 */
@Service
public class ContentPlanner {

    private static final Logger log = LoggerFactory.getLogger(ContentPlanner.class);

    private final VkArticleRepository articles;
    private final RuntimeConfig config;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ContentPlanner(VkArticleRepository articles, RuntimeConfig config) {
        this(articles, config, Clock.systemUTC());
    }

    ContentPlanner(VkArticleRepository articles, RuntimeConfig config, Clock clock) {
        this.articles = articles;
        this.config = config;
        this.clock = clock;
    }

    /** План на текущую неделю; если он уже есть — ничего не делает. Возвращает число новых записей. */
    public int planCurrentWeek() {
        ZoneId zone = zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (articles.countPlannedFrom(monday.toString()) > 0) return 0;

        int wantArticles = config.getVkArticlesPerWeek();
        int wantPolls = config.getVkPollsPerWeek();
        if (wantArticles + wantPolls == 0) return 0;

        List<LocalDate> days = contentDays(monday, today);
        if (days.isEmpty()) {
            log.warn("Контент-план VK: в настройке vkContentDays нет ни одного дня — план не составлен");
            return 0;
        }
        Set<String> used = articles.topicsUsedSince(today.minusDays(config.getVkTopicCooldownDays()).toString());

        List<ContentTopics.Topic> picked = new ArrayList<>();
        picked.addAll(pick(used, wantArticles, "article", true));    // сначала на данных
        picked.addAll(pick(used, wantArticles - picked.size(), "article", false));
        picked.addAll(pick(used, wantPolls, "poll", false));

        int created = 0;
        for (int i = 0; i < picked.size(); i++) {
            ContentTopics.Topic t = picked.get(i);
            VkArticle a = new VkArticle();
            a.setTopicKey(t.key());
            a.setKind(t.kind());
            a.setTitle(t.title());
            a.setPollOptions(t.pollOptions().isEmpty() ? "" : String.join("\n", t.pollOptions()));
            a.setStatus("planned");
            a.setPlannedFor(days.get(i % days.size()).toString());
            articles.save(a);
            created++;
        }
        log.info("Контент-план VK на неделю с {}: {} записей — {}", monday, created,
            picked.stream().map(ContentTopics.Topic::key).toList());
        return created;
    }

    /** Темы нужного вида, не бывшие в cooldown; dataBacked=true — только на данных. */
    private List<ContentTopics.Topic> pick(Set<String> used, int count, String kind, boolean dataBackedOnly) {
        List<ContentTopics.Topic> out = new ArrayList<>();
        if (count <= 0) return out;
        for (ContentTopics.Topic t : ContentTopics.ALL) {
            if (out.size() >= count) break;
            if (!t.kind().equals(kind)) continue;
            if (dataBackedOnly && !t.dataBacked()) continue;
            if (used.contains(t.key())) continue;
            out.add(t);
            used.add(t.key());
        }
        return out;
    }

    private static final java.util.Map<String, DayOfWeek> DAY_ALIASES = java.util.Map.of(
        "MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY,
        "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    /** Дни публикации на этой неделе, не раньше сегодняшнего. Принимает MON..SUN и полные имена. */
    List<LocalDate> contentDays(LocalDate monday, LocalDate today) {
        List<LocalDate> out = new ArrayList<>();
        String raw = config.getVkContentDays();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String key = part.trim().toUpperCase(Locale.ROOT);
            DayOfWeek dow = DAY_ALIASES.get(key.length() >= 3 ? key.substring(0, 3) : key);
            if (dow == null) {
                log.warn("День контента VK «{}» не распознан, пропущен", part.trim());
                continue;
            }
            LocalDate d = monday.with(TemporalAdjusters.nextOrSame(dow));
            if (!d.isBefore(today)) out.add(d);
        }
        out.sort(null);
        return out;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(config.getVkTimezone());
        } catch (Exception e) {
            return ZoneId.of("Europe/Moscow");
        }
    }
}
