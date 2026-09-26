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
 *   <li>не больше vkPostsPerWindow постов за окно — статьи и опросы считаются вместе с вакансиями;</li>
 *   <li>не чаще, чем раз в vkMinGapMinutes (после статьи — тоже).</li>
 * </ul>
 *
 * Адаптивный размер поста (23.09.2026). Постов в день фиксированное число, а одобренных
 * приходит то 10, то 100 — при одиночных постах очередь росла без предела (220 вакансий
 * при 6 постах в день). Теперь на каждом выпуске хвост сравнивается с числом постов,
 * оставшихся до конца дня ({@link #planPost}):
 * <ul>
 *   <li>хвост помещается в оставшиеся посты — одиночный пост с карточкой, как раньше;</li>
 *   <li>не помещается — первый пост окна остаётся одиночным (лучшая вакансия, «витрина»),
 *       остальные посты окна — подборки ровно такого размера, чтобы хвост разошёлся к
 *       концу дня (не больше vkDigestMaxSize);</li>
 *   <li>хвост больше, чем влезет даже в полные подборки, — все посты подборками по максимуму,
 *       а что не успело выйти за vkQueueMaxAgeHours, снимается с очереди.</li>
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
        List<Vacancy> reachable = vacancies.stream().filter(VkPostFormatter::hasApplyTarget).toList();
        if (reachable.size() < vacancies.size()) {
            log.info("VK: {} вакансий без ссылки и контакта для отклика — в очередь не ставим",
                vacancies.size() - reachable.size());
        }
        if (reachable.isEmpty()) return;
        vacancies = reachable;
        vacancyRepo.enqueueForVk(vacancies.stream().map(Vacancy::getId).toList());
        log.info("В очередь VK поставлено {} вакансий (в очереди всего {})",
            vacancies.size(), vacancyRepo.countVkQueued());
    }

    /**
     * После неудачного wall.post следующий выпуск не раньше чем через столько минут.
     * Без паузы сбой VK превращался в попытку на каждом двухминутном тике.
     */
    static final long FAILURE_BACKOFF_MINUTES = 15;
    /** Меньше трёх вакансий подборкой не выпускаем — это уже не подборка, а два поста в одном. */
    static final int MIN_DIGEST_SIZE = 3;

    private volatile Instant retryNotBefore = Instant.EPOCH;

    /** Глубина очереди в метрику vk_queue_depth — на каждом тике, и вне окон тоже. */
    public void refreshQueueMetric() {
        metrics.setVkQueueDepth(vacancyRepo.countVkQueued());
    }

    /** Тик планировщика: выпустить один пост, если окно открыто и лимиты позволяют. */
    public void publishDue() {
        if (!runtimeConfig.isVkEnabled()) return;
        Window window = currentWindow();
        if (window == null) return;
        if (clock.instant().isBefore(retryNotBefore)) return;

        String windowStartIso = window.start.toInstant().toString();
        int postsThisWindow = vacancyRepo.countVkPublishedSince(windowStartIso)
            + (articles != null ? articles.countPublishedSince(windowStartIso) : 0);
        if (postsThisWindow >= runtimeConfig.getVkPostsPerWindow()) return;

        String last = latest(vacancyRepo.lastVkPublishedAt(), articles != null ? articles.lastPublishedAt() : null);
        if (last != null) {
            Duration sinceLast = Duration.between(parseInstant(last), clock.instant());
            if (sinceLast.toMinutes() < runtimeConfig.getVkMinGapMinutes()) return;
        }

        // Контент (статья/опрос) идёт первым в своём слоте — обеденное окно, когда читают
        // дольше; занимает один пост окна, как и вакансия, чтобы лента не переполнялась.
        if (publishContentIfDue(window)) return;

        expireStale();
        int backlog = vacancyRepo.countVkQueued();
        if (backlog == 0) return;
        int size = planPost(backlog, postsThisWindow, remainingWindowsAfter(window));
        List<Vacancy> next = vacancyRepo.findVkQueued(size);
        // Попавшие в очередь до фильтра в enqueue вакансии без отклика снимаем здесь же,
        // а не публикуем с пустым номером в комментарии.
        List<Long> unreachable = next.stream().filter(v -> !VkPostFormatter.hasApplyTarget(v)).map(Vacancy::getId).toList();
        if (!unreachable.isEmpty()) {
            vacancyRepo.expireVkQueued(unreachable);
            log.info("Очередь VK: снято {} вакансий без ссылки и контакта для отклика", unreachable.size());
            // Перечитываем: иначе пост с одной «неоткликаемой» вакансией не выходил в этом окне вовсе
            next = vacancyRepo.findVkQueued(size).stream().filter(VkPostFormatter::hasApplyTarget).toList();
        }
        if (next.isEmpty()) return;
        if (next.size() == 1) {
            publishOne(next.get(0));
        } else {
            publishDigest(next, window, backlog);
        }
    }

    /**
     * Сколько вакансий выпустить этим постом: 1 — одиночный пост, больше — подборка.
     *
     * @param backlog         вакансий в очереди
     * @param postsThisWindow постов, уже вышедших в текущем окне
     * @param windowsAfter    сколько окон ещё впереди сегодня (без текущего)
     */
    int planPost(int backlog, int postsThisWindow, int windowsAfter) {
        int perWindow = runtimeConfig.getVkPostsPerWindow();
        int maxDigest = Math.min(runtimeConfig.getVkDigestMaxSize(), VkPostFormatter.MAX_DIGEST_ITEMS);
        if (maxDigest < 2) return 1;

        int postsLeft = (perWindow - postsThisWindow) + windowsAfter * perWindow;
        if (backlog <= postsLeft) return 1;   // всё и так выйдет одиночными — подборка не нужна

        // Первый пост каждого окна — «витрина» с одной вакансией и карточкой; остальные
        // посты окна — подборки. При одном посте на окно витрин нет: иначе подборок не было бы вовсе.
        boolean showcases = perWindow >= 2;
        int showcasesLeft = showcases ? (postsThisWindow == 0 ? 1 : 0) + windowsAfter : 0;
        int digestsLeft = postsLeft - showcasesLeft;
        boolean overflow = backlog > showcasesLeft + (long) digestsLeft * maxDigest;
        if (showcases && postsThisWindow == 0 && !overflow) return 1;

        int slots = overflow ? postsLeft : digestsLeft;
        int remaining = overflow ? backlog : backlog - showcasesLeft;
        int size = (remaining + slots - 1) / Math.max(1, slots);
        if (size <= 1) return 1;
        return Math.max(MIN_DIGEST_SIZE, Math.min(maxDigest, size));
    }

    /** Сколько окон сегодня начинается после текущего. */
    private int remainingWindowsAfter(Window window) {
        LocalTime current = window.start.toLocalTime();
        return (int) windowStarts().stream().filter(t -> t.isAfter(current)).count();
    }

    /** Снять с очереди то, что простояло дольше vkQueueMaxAgeHours или закрыто на hh. */
    private void expireStale() {
        String cutoff = clock.instant().minus(Duration.ofHours(runtimeConfig.getVkQueueMaxAgeHours())).toString();
        int expired = vacancyRepo.expireVkQueue(cutoff);
        if (expired > 0) {
            log.info("Очередь VK: снято {} вакансий — простояли дольше {} ч или закрыты на hh",
                expired, runtimeConfig.getVkQueueMaxAgeHours());
        }
    }

    private void publishDigest(List<Vacancy> items, Window window, int backlog) {
        String screenName = vkNotifier.screenName();
        String text = VkPostFormatter.digestPost(items, screenName, window.start.getHour());
        Long postId = vkNotifier.postReturningId(text, digestCardFor(items, window));
        if (postId == null) {
            retryNotBefore = clock.instant().plus(Duration.ofMinutes(FAILURE_BACKOFF_MINUTES));
            log.warn("VK: подборка из {} вакансий не отправлена — вакансии остаются в очереди, повтор не раньше чем через {} мин",
                items.size(), FAILURE_BACKOFF_MINUTES);
            return;
        }
        vacancyRepo.markVkSentBatch(items.stream().map(Vacancy::getId).toList(), String.valueOf(postId));
        metrics.recordVkPost();
        metrics.recordVkPostSize(items.size());
        log.info("Опубликована в VK подборка из {} вакансий (post_id={}, id={}, в очереди было {}, осталось {})",
            items.size(), postId, items.stream().map(v -> String.valueOf(v.getId())).toList(), backlog,
            vacancyRepo.countVkQueued());

        String comment = VkPostFormatter.digestComment(items);
        if (comment != null && !vkNotifier.comment(postId, comment)) {
            log.warn("VK: комментарий со ссылками под подборкой {} не создан — ссылки на отклик потеряны", postId);
        }
    }

    private String digestCardFor(List<Vacancy> items, Window window) {
        if (!runtimeConfig.isVkCardsEnabled()) return null;
        try {
            int hour = window.start.getHour();
            String kicker = hour < 12 ? "утренняя подборка" : hour < 17 ? "дневная подборка" : "вечерняя подборка";
            byte[] png = com.hh.gui.content.CardImageRenderer.digestCard(items, kicker, communityLabel());
            return vkNotifier.uploadWallPhoto(png, "digest-" + items.get(0).getId() + ".png");
        } catch (Exception e) {
            log.warn("Карточка подборки не создана: {}", e.getMessage());
            return null;
        }
    }

    /** Более поздняя из двух ISO-меток (любая может быть null). */
    private static String latest(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return parseInstant(a).isAfter(parseInstant(b)) ? a : b;
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
            retryNotBefore = clock.instant().plus(Duration.ofMinutes(FAILURE_BACKOFF_MINUTES));
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
            // Раньше вакансия помечалась failed «до следующего enqueue», но enqueue зовётся
            // только для новых id — она не возвращалась никогда. Теперь остаётся в очереди,
            // а повтор сдвигается паузой; безнадёжную снимет срок жизни очереди.
            retryNotBefore = clock.instant().plus(Duration.ofMinutes(FAILURE_BACKOFF_MINUTES));
            log.warn("VK: пост не отправлен, вакансия id={} остаётся в очереди, повтор не раньше чем через {} мин",
                v.getId(), FAILURE_BACKOFF_MINUTES);
            return;
        }
        vacancyRepo.markVkSent(v.getId(), String.valueOf(postId));
        metrics.recordVkPost();
        metrics.recordVkPostSize(1);
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
