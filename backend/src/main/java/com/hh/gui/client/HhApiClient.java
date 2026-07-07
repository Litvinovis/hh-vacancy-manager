package com.hh.gui.client;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.Vacancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * HH.ru RSS client — discovery only.
 *
 * RSS is the one hh.ru endpoint that works with a plain HTTP client (api.hh.ru
 * and the HTML vacancy pages are both behind DDoS-Guard, which only a real
 * browser session passes — see ScraperClient). RSS also never includes the
 * actual job description, only company/date/region/salary metadata, so there
 * is nothing worth parsing out of it beyond the vacancy ID and title: real
 * content comes from ScraperClient once a new ID is found here.
 */
@Component
public class HhApiClient {

    private static final Logger log = LoggerFactory.getLogger(HhApiClient.class);
    private static final String RSS_BASE = "https://hh.ru/search/vacancy/rss";

    private final RuntimeConfig runtimeConfig;

    public HhApiClient(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * Discover vacancy IDs via RSS for a single query.
     * RSS returns up to 20 latest vacancies per query — no pagination.
     */
    public List<Vacancy> fetchRss(String query, int area, String schedule, int salaryMin) {
        List<Vacancy> results = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder(RSS_BASE);
            url.append("?text=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            url.append("&area=").append(area);
            url.append("&per_page=20");
            if (schedule != null && !schedule.isEmpty()) {
                url.append("&schedule=").append(schedule);
            }
            if (salaryMin > 0) {
                url.append("&salary=").append(salaryMin);
            }

            log.debug("HH RSS: {}", url);

            String xml = httpGet(url.toString());
            if (xml == null) return results;

            Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                Vacancy v = parseRssItem(item);
                if (v != null) {
                    v.setSource("hh");
                    v.setSourceQuery(query);
                    v.setRemote("remote".equals(schedule));
                    results.add(v);
                }
            }

            log.info("HH RSS: получено {} ID для '{}' (area={}, schedule={})", results.size(), query, area, schedule);
        } catch (Exception e) {
            log.error("Ошибка HH RSS для '{}': {}", query, e.getMessage());
        }
        return results;
    }

    /**
     * Discover vacancy IDs across multiple queries, deduplicating by HH ID.
     */
    public List<Vacancy> fetchMultipleRss(List<String> queries, int area, String schedule, int salaryMin) {
        Map<String, Vacancy> seen = new LinkedHashMap<>();
        for (String query : queries) {
            List<Vacancy> batch = fetchRss(query, area, schedule, salaryMin);
            for (Vacancy v : batch) {
                seen.putIfAbsent(v.getHhId(), v);
            }
            try {
                Thread.sleep(runtimeConfig.getRequestDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ArrayList<>(seen.values());
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/rss+xml");
        conn.setConnectTimeout(runtimeConfig.getHttpConnectTimeoutMs() > 0 ? runtimeConfig.getHttpConnectTimeoutMs() : 15000);
        conn.setReadTimeout(runtimeConfig.getHttpReadTimeoutMs() > 0 ? Math.min(runtimeConfig.getHttpReadTimeoutMs(), 30000) : 30000);

        int code = conn.getResponseCode();
        if (code != 200) {
            log.warn("HTTP {} от {}", code, urlStr);
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /** Extract just the hh_id, title, link and publish date — the real content comes from ScraperClient. */
    private Vacancy parseRssItem(Element item) {
        try {
            String title = getText(item, "title");
            String link = getText(item, "link");
            String guid = getText(item, "guid");
            String pubDate = getText(item, "pubDate");

            if (title == null || link == null) return null;

            String hhId = link.replaceAll(".*/vacancy/(\\d+).*", "$1");
            if (hhId.isEmpty()) hhId = guid;
            if (hhId == null || hhId.isEmpty() || !hhId.matches("\\d+")) return null;

            Vacancy v = new Vacancy();
            v.setHhId(hhId);
            v.setTitle(title);
            v.setUrl(link);
            v.setPublishedAt(pubDate);
            v.setStatus("new");
            v.setAiVerdict("pending");
            v.setAiScore(0);
            v.setScrapeStatus("pending");
            v.setCreatedAt(Instant.now().toString());
            return v;
        } catch (Exception e) {
            log.warn("Не удалось разобрать RSS-элемент: {}", e.getMessage());
            return null;
        }
    }

    private String getText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }
}
