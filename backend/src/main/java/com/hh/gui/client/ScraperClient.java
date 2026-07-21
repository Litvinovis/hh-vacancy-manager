package com.hh.gui.client;

import tools.jackson.databind.ObjectMapper;
import com.hh.gui.config.RuntimeConfig;
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
 * Client for the hh-vacancy-scraper sidecar (scraper/server.js).
 *
 * That service drives a real headless-browser session to fetch full hh.ru
 * vacancy pages — api.hh.ru and plain HTTP requests to hh.ru/vacancy/{id} are
 * both blocked by DDoS-Guard, but a real browser session passes cleanly.
 * This client just talks HTTP to that local sidecar.
 */
@Component
public class ScraperClient {

    private static final Logger log = LoggerFactory.getLogger(ScraperClient.class);

    @Value("${app.scraper.url:http://127.0.0.1:8095}")
    private String scraperBaseUrl;

    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScraperClient(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public record ScrapeResult(
        boolean ok, String reason,
        String title, String employerName, String descriptionHtml,
        Integer salaryFrom, Integer salaryTo, String currency, Boolean salaryGross,
        String city, String region, String street,
        String experience, String employment, List<String> keySkills,
        boolean trustedEmployer, String datePosted, String validThrough) {

        static ScrapeResult failure(String reason) {
            return new ScrapeResult(false, reason, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), false, null, null);
        }
    }

    public ScrapeResult scrape(String hhId) {
        try {
            String url = scraperBaseUrl + "/scrape?hhId=" + URLEncoder.encode(hhId, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            // The sidecar serializes all page loads through one queue with anti-bot
            // delays, so under load a response legitimately takes several minutes —
            // and a client timeout discards work the sidecar still completes.
            conn.setReadTimeout(scraperReadTimeoutMs());

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);

            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);
            if (!Boolean.TRUE.equals(json.get("ok"))) {
                String reason = String.valueOf(json.getOrDefault("reason", "unknown"));
                log.warn("Скрейпинг не удался для hh_id={}: {}", hhId, reason);
                return ScrapeResult.failure(reason);
            }

            @SuppressWarnings("unchecked")
            List<String> keySkills = (List<String>) json.getOrDefault("keySkills", List.of());

            return new ScrapeResult(
                true, null,
                str(json.get("title")), str(json.get("employerName")), str(json.get("descriptionHtml")),
                asInt(json.get("salaryFrom")), asInt(json.get("salaryTo")),
                str(json.get("currency")), (Boolean) json.get("salaryGross"),
                str(json.get("city")), str(json.get("region")), str(json.get("street")),
                str(json.get("experience")), str(json.get("employment")), keySkills,
                Boolean.TRUE.equals(json.get("trustedEmployer")),
                str(json.get("datePosted")), str(json.get("validThrough")));
        } catch (Exception e) {
            log.error("Ошибка обращения к scraper-сервису для hh_id={}: {}", hhId, e.getMessage());
            return ScrapeResult.failure("client_error: " + e.getMessage());
        }
    }

    private int scraperReadTimeoutMs() {
        return runtimeConfig.getScraperReadTimeoutMs() > 0 ? runtimeConfig.getScraperReadTimeoutMs() : 240000;
    }

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * One vacancy card as listed on an hh.ru search-results page (see /search on the
     * sidecar). snippet is the card's short duties/requirements teaser — the richest
     * signal the AI prescreen gets without paying for a full page scrape (null for
     * RSS-discovered candidates, which carry a title only).
     */
    public record SearchHit(String hhId, String title, String employerName, String salaryRawText, String address,
                            String snippet, String url) {}

    public record SearchPageResult(boolean ok, String reason, List<SearchHit> items, String lastPageLabel) {
        static SearchPageResult failure(String reason) {
            return new SearchPageResult(false, reason, List.of(), null);
        }
    }

    /**
     * EXPERIMENTAL, manual-trigger only (see VacancyPipelineService.discoverFromUrl) —
     * drives a real hh.ru search-results page (a URL the caller built themselves using
     * hh.ru's own filter UI) through the same headless-browser session used for
     * /scrape, instead of the 20-results-no-pagination RSS feed HhApiClient uses for
     * the normal automated discovery.
     */
    public SearchPageResult searchByUrl(String searchUrl, int page) {
        try {
            String u = scraperBaseUrl + "/search?url=" + URLEncoder.encode(searchUrl, StandardCharsets.UTF_8)
                + "&page=" + page;
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(scraperReadTimeoutMs());

            int code = conn.getResponseCode();
            String body = HttpUtil.readBody(conn, code);

            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);
            if (!Boolean.TRUE.equals(json.get("ok"))) {
                String reason = String.valueOf(json.getOrDefault("reason", "unknown"));
                log.warn("Поиск по ссылке не удался (страница {}): {}", page, reason);
                return SearchPageResult.failure(reason);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) json.getOrDefault("items", List.of());
            List<SearchHit> items = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                String hhId = str(item.get("hhId"));
                if (hhId == null || hhId.isBlank()) continue;
                items.add(new SearchHit(hhId, str(item.get("title")), str(item.get("employerName")),
                    str(item.get("salaryRawText")), str(item.get("address")), str(item.get("snippet")),
                    str(item.get("url"))));
            }
            String lastPageLabel = str(json.get("lastPageLabel"));
            log.debug("Скрейпер /search (страница {}): {} карточек, последняя страница пагинатора: {}",
                page, items.size(), lastPageLabel);
            return new SearchPageResult(true, null, items, lastPageLabel);
        } catch (Exception e) {
            log.error("Ошибка обращения к scraper-сервису (поиск по ссылке, страница {}): {}", page, e.getMessage());
            return SearchPageResult.failure("client_error: " + e.getMessage());
        }
    }
}
