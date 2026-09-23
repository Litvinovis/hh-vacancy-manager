package com.hh.gui.service;

import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.repository.VkArticleRepository;
import com.hh.gui.util.HttpUtil;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Вовлечённость сообщества VK для Grafana (24.09.2026): участники и реакции на посты.
 *
 * До этого о VK было известно только, сколько постов отправлено, — дошли ли они до людей,
 * видно не было. Главный вопрос, на который отвечают эти метрики: что заходит лучше —
 * одиночная вакансия с карточкой, подборка, статья или опрос (VkPublishQueue решает, чем
 * выпускать очередь, и без обратной связи это решение вслепую).
 *
 * Метрики:
 * <ul>
 *   <li>vk_community_members — участников сообщества (групповой токен, groups.getById);</li>
 *   <li>vk_posts_recent{type} — постов за 7 дней по типу: vacancy, digest, article, poll, other;</li>
 *   <li>vk_posts_engagement{type,metric} — сумма views/likes/comments/reposts этих постов.</li>
 * </ul>
 * Стену групповой токен читать не может (error 27), поэтому wall.get — пользовательским
 * токеном VK ID. Раз в 2 часа — два вызова API, ~720 в месяц при лимите 10 тыс.
 */
@Component
public class VkEngagementTracker {

    private static final Logger log = LoggerFactory.getLogger(VkEngagementTracker.class);
    static final Duration WINDOW = Duration.ofDays(7);
    private static final List<String> METRICS = List.of("views", "likes", "comments", "reposts");

    @Value("${app.vk.api-base-url:https://api.vk.com}")
    private String apiBaseUrl;
    @Value("${app.vk.access-token:}")
    private String groupToken;
    @Value("${app.vk.group-id:}")
    private String groupId;
    @Value("${app.vk.api-version:5.199}")
    private String apiVersion;

    @Autowired(required = false)
    private VkIdTokenService vkIdTokens;

    private final VacancyRepository vacancyRepo;
    private final VkArticleRepository articleRepo;
    private final AtomicInteger members = new AtomicInteger(-1);
    private final MultiGauge posts;
    private final MultiGauge engagement;
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    public VkEngagementTracker(VacancyRepository vacancyRepo, VkArticleRepository articleRepo, MeterRegistry registry) {
        this.vacancyRepo = vacancyRepo;
        this.articleRepo = articleRepo;
        Gauge.builder("vk_community_members", members, a -> a.get() < 0 ? Double.NaN : a.get())
            .tag("application", "hh-gui").description("Участников сообщества VK").register(registry);
        this.posts = MultiGauge.builder("vk_posts_recent").tag("application", "hh-gui")
            .description("Постов сообщества VK за 7 дней по типу").register(registry);
        this.engagement = MultiGauge.builder("vk_posts_engagement").tag("application", "hh-gui")
            .description("Просмотры/лайки/комментарии/репосты постов VK за 7 дней по типу").register(registry);
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT2H")
    public void refresh() {
        if (groupId == null || groupId.isBlank()) return;
        refreshMembers();
        refreshPosts();
    }

    private void refreshMembers() {
        if (groupToken == null || groupToken.isBlank()) return;
        try {
            Map<?, ?> resp = call("groups.getById", Map.of("group_id", groupId, "fields", "members_count"), groupToken);
            Object r = resp.get("response");
            Object groups = r instanceof Map<?, ?> m ? m.get("groups") : r;
            Map<?, ?> g = (Map<?, ?>) ((List<?>) groups).get(0);
            if (g.get("members_count") instanceof Number n) members.set(n.intValue());
        } catch (Exception e) {
            log.warn("VK: число участников не получено: {}", e.getMessage());
        }
    }

    private void refreshPosts() {
        String userToken = vkIdTokens != null ? vkIdTokens.accessToken().orElse(null) : null;
        if (userToken == null) return;   // без пользовательского токена стену не прочитать
        try {
            Map<?, ?> resp = call("wall.get", Map.of("owner_id", "-" + groupId, "count", "100"), userToken);
            List<?> items = (List<?>) ((Map<?, ?>) resp.get("response")).get("items");
            Map<String, Integer> sizes = vacancyRepo.vkPostSizes();
            Map<String, String> kinds = articleRepo.publishedPostKinds();
            long since = Instant.now().minus(WINDOW).getEpochSecond();

            Map<String, int[]> byType = new LinkedHashMap<>();   // [posts, views, likes, comments, reposts]
            for (String t : List.of("vacancy", "digest", "article", "poll", "other")) byType.put(t, new int[5]);
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> post)) continue;
                if (!(post.get("date") instanceof Number date) || date.longValue() < since) continue;
                if (Integer.valueOf(1).equals(post.get("is_pinned"))) continue;   // закреп старше окна искажал бы суммы
                String type = typeOf(String.valueOf(post.get("id")), sizes, kinds);
                int[] acc = byType.get(type);
                acc[0]++;
                acc[1] += count(post, "views");
                acc[2] += count(post, "likes");
                acc[3] += count(post, "comments");
                acc[4] += count(post, "reposts");
            }
            List<MultiGauge.Row<?>> postRows = new ArrayList<>();
            List<MultiGauge.Row<?>> engagementRows = new ArrayList<>();
            byType.forEach((type, acc) -> {
                postRows.add(MultiGauge.Row.of(Tags.of("type", type), acc[0]));
                for (int i = 0; i < METRICS.size(); i++) {
                    engagementRows.add(MultiGauge.Row.of(Tags.of("type", type, "metric", METRICS.get(i)), acc[i + 1]));
                }
            });
            posts.register(postRows, true);
            engagement.register(engagementRows, true);
        } catch (Exception e) {
            log.warn("VK: реакции на посты не получены: {}", e.getMessage());
        }
    }

    /** Тип поста по нашим записям: сколько вакансий в нём и не статья ли это. */
    static String typeOf(String postId, Map<String, Integer> sizes, Map<String, String> kinds) {
        String kind = kinds.get(postId);
        if (kind != null) return "poll".equals(kind) ? "poll" : "article";
        Integer n = sizes.get(postId);
        if (n == null) return "other";
        return n > 1 ? "digest" : "vacancy";
    }

    private static int count(Map<?, ?> post, String field) {
        return post.get(field) instanceof Map<?, ?> m && m.get("count") instanceof Number n ? n.intValue() : 0;
    }

    private Map<?, ?> call(String method, Map<String, String> params, String token) throws Exception {
        StringBuilder body = new StringBuilder();
        for (var e : params.entrySet()) {
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append('&');
        }
        body.append("access_token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8))
            .append("&v=").append(URLEncoder.encode(apiVersion, StandardCharsets.UTF_8));
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiBaseUrl + "/method/" + method).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = HttpUtil.readBody(conn, code);
        Map<?, ?> parsed = mapper.readValue(resp, Map.class);
        if (code != 200 || parsed.containsKey("error")) {
            throw new IllegalStateException(method + ": " + (parsed.containsKey("error") ? parsed.get("error") : "HTTP " + code));
        }
        return parsed;
    }
}
