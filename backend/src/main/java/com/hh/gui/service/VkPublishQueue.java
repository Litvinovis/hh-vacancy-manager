package com.hh.gui.service;

import com.hh.gui.ai.AiMetrics;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.repository.VkArticleRepository;
import com.hh.gui.model.VkArticle;
import com.hh.gui.util.VkPostFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Очередь публикаций в VK со своим расписанием — отдельно от Telegram.
 *
 * Зачем (20.09.2026): VK получал копию каждого Telegram-батча в момент отправки, и после
 * ночного сетевого сбоя в сообщество улетело 20 постов за 4 минуты. Для умной ленты ВК это
 * худший сценарий: всплеск одного источника режет охват, а подписчик видит стену одинаковых
 * постов и скрывает сообщество. Теперь вакансия встаёт в очередь, а выпускается порциями в
 * окна активности аудитории (утро/обед/вечер по МСК), лучшие по скору — первыми.
 *
 * Правила выпуска, все настраиваются в RuntimeConfig:
 * <ul>
 *   <li>только внутри окна: от времени начала окна до следующего окна (последнее — до полуночи);</li>
 *   <li>не больше vkPostsPerWindow постов за окно;</li>
 *   <li>не чаще, чем раз в vkMinGapMinutes.</li>
 * </ul>
 * Часы инжектируются — расписание проверяется тестами без ожидания реального времени.
 */
@Service
public class VkPublishQueue {

    private static final Logger log = LoggerFactory.getLogger(VkPublishQueue.class);

    private final VacancyRepository vacancyRepo;
    private final VkNotifier vkNotifier;
    private final RuntimeConfig runtimeConfig;
    private final AiMetrics metrics;
    private final Clock clock;
    /** Может быть null в тестах очереди вакансий — тогда контентный слот просто пропускается. */
    private final VkArticleRepository articles;

    @org.springframework.beans.factory.annotation.Autowired
    public VkPublishQueue(VacancyRepository vacancyRepo, VkNotifier vkNotifier,
                          RuntimeConfig runtimeConfig, AiMetrics metrics, VkArticleRepository articles) {
        this(vacancyRepo, vkNotifier, runtimeConfig, metrics, Clock.systemUTC(), articles);
    }

    VkPublishQueue(VacancyRepository vacancyRepo, VkNotifier vkNotifier,
                   RuntimeConfig runtimeConfig, AiMetrics metrics, Clock clock) {
        this(vacancyRepo, vkNotifier, runtimeConfig, metrics, clock, null);
    }

    VkPublishQueue(VacancyRepository vacancyRepo, VkNotifier vkNotifier,
                   RuntimeConfig runtimeConfig, AiMetrics metrics, Clock clock, VkArticleRepository articles) {
        this.vacancyRepo = vacancyRepo;
        this.vkNotifier = vkNotifier;
        this.runtimeConfig = runtimeConfig;
        this.metrics = metrics;
        this.clock = clock;
        this.articles = articles;
    }

    /** Ставит вакансии в очередь VK. Вызывается там, где раньше был немедленный кросс-пост. */
    public void enqueue(List<Vacancy> vacancies) {
        if (vacancies.isEmpty()) return;
        vacancyRepo.enqueueForVk(vacancies.stream().map(Vacancy::getId).toList());
        log.info("В очередь VK поставлено {} вакансий (в очереди всего {})",
            vacancies.size(), vacancyRepo.countVkQueued());
    }

    /** Тик планировщика: выпустить один пост, если окно открыто и лимиты позволяют. */
    public void publishDue() {
        if (!runtimeConfig.isVkEnabled()) return;
        Window window = currentWindow();
        if (window == null) return;

        int sentThisWindow = vacancyRepo.countVkPublishedSince(window.start.toInstant().toString());
        if (sentThisWindow >= runtimeConfig.getVkPostsPerWindow()) return;

        String last = vacancyRepo.lastVkPublishedAt();
        if (last != null) {
            Duration sinceLast = Duration.between(parseInstant(last), clock.instant());
            if (sinceLast.toMinutes() < runtimeConfig.getVkMinGapMinutes()) return;
        }

        // Контент (статья/опрос) идёт первым в своём слоте — обеденное окно, когда читают
        // дольше; занимает один пост окна, как и вакансия, чтобы лента не переполнялась.
        if (publishContentIfDue(window)) return;

        List<Vacancy> next = vacancyRepo.findVkQueued(1);
        if (next.isEmpty()) return;
        publishOne(next.get(0));
    }

    /** Второе окно дня (или единственное) — слот для статей и опросов. */
    private boolean publishContentIfDue(Window window) {
        if (articles == null) return false;
        List<LocalTime> starts = windowStarts();
        LocalTime contentSlot = starts.size() >= 2 ? starts.get(1) : starts.get(0);
        if (!window.start.toLocalTime().equals(contentSlot)) return false;
        String today = window.start.toLocalDate().toString();
        var due = articles.nextToPublish(today);
        if (due.isEmpty()) return false;
        VkArticle a = due.get();
        Long postId;
        String card = cardFor(a);
        if (a.isPoll()) {
            List<String> options = List.of(a.getPollOptions().split("\n"));
            postId = vkNotifier.postPoll(a.getTitle(), a.getTitle(), options, card);
        } else {
            postId = vkNotifier.postReturningId(a.getBody(), card);
        }
        a.setPublishedAt(clock.instant().toString());
        if (postId == null) {
            a.setStatus("failed");
            articles.update(a);
            log.warn("VK: {} «{}» не опубликован(а)", a.isPoll() ? "опрос" : "статья", a.getTitle());
            return true;   // слот использован — попытка была; вакансию в этот тик не гоним
        }
        a.setStatus("published");
        a.setVkPostId(String.valueOf(postId));
        articles.update(a);
        metrics.recordVkPost();
        log.info("Опубликован(а) в VK {} «{}» (post_id={})", a.isPoll() ? "опрос" : "статья", a.getTitle(), postId);
        return true;
    }

    private void publishOne(Vacancy v) {
        String screenName = vkNotifier.screenName();
        Long postId = vkNotifier.postReturningId(VkPostFormatter.publicPost(v, screenName), cardFor(v));
        if (postId == null) {
            vacancyRepo.markVkFailed(v.getId());
            log.warn("VK: пост не отправлен, вакансия id={} помечена failed (вернётся в очередь при следующем enqueue)", v.getId());
            return;
        }
        vacancyRepo.markVkSent(v.getId(), String.valueOf(postId));
        metrics.recordVkPost();
        log.info("Опубликовано в VK: вакансия id={} «{}» (post_id={}, в очереди осталось {})",
            v.getId(), v.getTitle(), postId, vacancyRepo.countVkQueued());

        String comment = VkPostFormatter.applyComment(v);
        if (comment != null && !vkNotifier.comment(postId, comment)) {
            // Пост уже вышел — не откатываем, но без ссылки читателю некуда откликаться.
            log.warn("VK: комментарий со ссылкой под постом {} не создан — ссылка на отклик потеряна", postId);
        }
    }


    /**
     * Карточка к посту, загруженная в VK; null — если нарисовать или загрузить не удалось.
     * Картинка — усиление, а не условие: без неё пост всё равно уходит, просто текстом.
     */
    private String cardFor(Vacancy v) {
        if (!runtimeConfig.isVkCardsEnabled()) return null;
        try {
            byte[] png = com.hh.gui.content.CardImageRenderer.vacancyCard(v, communityLabel());
            return vkNotifier.uploadWallPhoto(png, "vacancy-" + v.getId() + ".png");
        } catch (Exception e) {
            log.warn("Карточка для вакансии id={} не создана: {}", v.getId(), e.getMessage());
            return null;
        }
    }

    private String cardFor(VkArticle a) {
        if (!runtimeConfig.isVkCardsEnabled()) return null;
        try {
            byte[] png = com.hh.gui.content.CardImageRenderer.articleCard(
                a.getTitle(), a.isPoll() ? "опрос" : "разбор", communityLabel());
            return vkNotifier.uploadWallPhoto(png, (a.isPoll() ? "poll-" : "article-") + a.getId() + ".png");
        } catch (Exception e) {
            log.warn("Карточка для статьи id={} не создана: {}", a.getId(), e.getMessage());
            return null;
        }
    }

    /** Подпись на карточке: vk.com/<имя>, чтобы репост уводил обратно в сообщество. */
    private String communityLabel() {
        String name = vkNotifier.screenName();
        return name != null ? "vk.com/" + name : "Интересная удалёнка";
    }

    /** Окно, в котором находится «сейчас», или null вне окон / при некорректной настройке. */
    Window currentWindow() {
        ZoneId zone = zone();
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        List<LocalTime> starts = windowStarts();
        if (starts.isEmpty()) return null;
        ZonedDateTime windowStart = null;
        for (LocalTime t : starts) {
            ZonedDateTime candidate = now.with(t);
            if (!candidate.isAfter(now)) windowStart = candidate;   // последнее начало окна ≤ сейчас
        }
        // До первого окна дня — ещё «ночь»: ничего не публикуем, вчерашнее окно закрыто полуночью.
        return windowStart == null ? null : new Window(windowStart);
    }

    List<LocalTime> windowStarts() {
        List<LocalTime> result = new ArrayList<>();
        String raw = runtimeConfig.getVkPublishWindows();
        if (raw == null) return result;
        for (String part : raw.split(",")) {
            try {
                result.add(LocalTime.parse(part.trim()));
            } catch (DateTimeParseException e) {
                log.warn("Окно публикации VK «{}» не разобрано, пропущено", part.trim());
            }
        }
        result.sort(null);
        return result;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(runtimeConfig.getVkTimezone());
        } catch (Exception e) {
            log.warn("Часовой пояс VK «{}» не распознан, используется Europe/Moscow", runtimeConfig.getVkTimezone());
            return ZoneId.of("Europe/Moscow");
        }
    }

    private static Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;   // неразборчивая метка — считаем, что давно
        }
    }

    record Window(ZonedDateTime start) {}
}
