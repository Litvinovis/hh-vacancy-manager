package com.hh.gui.client;

import tools.jackson.databind.ObjectMapper;
import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only counterpart of VkNotifier — fetches wall posts from OTHER public VK
 * communities (see CommentRadarService) using a PERSONAL user access token, never the
 * group posting token configured for VkNotifier. The two must never be conflated: the
 * group token can't call wall.get at all, even on its own wall (verified live —
 * "Group authorization failed", error_code 27), so reading anyone else's wall needs a
 * real user token with read rights.
 */
@Component
public class VkReaderClient {

    private static final Logger log = LoggerFactory.getLogger(VkReaderClient.class);

    @Value("${app.vk.api-base-url:https://api.vk.com}")
    private String apiBaseUrl;

    @Value("${app.vk.user-read-token:}")
    private String userReadToken;

    @Value("${app.vk.api-version:5.199}")
    private String apiVersion;

    private final ObjectMapper mapper = new ObjectMapper();

    /** @param postId  "wall<owner_id>_<id>" — VK's own object-id format, also used as
     *                 the unique key in comment_radar_findings.
     *  @param link    https://vk.com/<postId>, ready to open directly. */
    public record VkPost(String postId, String text, long date, String link) {}

    /**
     * Fetches up to {@code count} most recent wall posts from a public community.
     * Empty list (never an exception) on missing config, a transport failure, or a VK
     * API error — a scan loop over several source communities shouldn't die because one
     * of them is unreachable or the token expired.
     */
    public List<VkPost> fetchWallPosts(String communityId, int count) {
        if (userReadToken == null || userReadToken.isEmpty()) {
            log.warn("VK personal read token не настроен (app.vk.user-read-token) — радар не может читать паблики");
            return List.of();
        }
        try {
            String url = apiBaseUrl + "/method/wall.get?owner_id=" + URLEncoder.encode("-" + communityId, StandardCharsets.UTF_8)
                + "&count=" + count
                + "&access_token=" + URLEncoder.encode(userReadToken, StandardCharsets.UTF_8)
                + "&v=" + URLEncoder.encode(apiVersion, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);
            if (code != 200) {
                log.error("Ошибка VK API {} при чтении сообщества {}: {}", code, communityId, body);
                return List.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);
            if (json.containsKey("error")) {
                log.error("VK API вернул ошибку при чтении сообщества {}: {}", communityId, body);
                return List.of();
            }
            return parseItems(json, communityId);
        } catch (Exception e) {
            log.error("Не удалось прочитать стену VK-сообщества {}: {}", communityId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<VkPost> parseItems(Map<String, Object> json, String communityId) {
        Object responseObj = json.get("response");
        if (!(responseObj instanceof Map)) return List.of();
        Object itemsObj = ((Map<String, Object>) responseObj).get("items");
        if (!(itemsObj instanceof List)) return List.of();

        List<VkPost> posts = new ArrayList<>();
        for (Object raw : (List<Object>) itemsObj) {
            if (!(raw instanceof Map<?, ?> item)) continue;
            Object idVal = item.get("id");
            Object ownerVal = item.get("owner_id");
            if (!(idVal instanceof Number) || !(ownerVal instanceof Number)) {
                log.warn("Пост без id/owner_id в сообществе {}, пропускаем: {}", communityId, item);
                continue;
            }
            long id = ((Number) idVal).longValue();
            long ownerId = ((Number) ownerVal).longValue();
            String text = item.get("text") instanceof String s ? s : "";
            long date = item.get("date") instanceof Number n ? n.longValue() : 0L;
            String postId = "wall" + ownerId + "_" + id;
            posts.add(new VkPost(postId, text, date, "https://vk.com/" + postId));
        }
        return posts;
    }
}
