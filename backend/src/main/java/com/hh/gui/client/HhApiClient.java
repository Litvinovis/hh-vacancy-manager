package com.hh.gui.client;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.Vacancy;
import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
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
    // A 2026-08-04..07 incident showed ~73% of RSS fetches failing (DNS/connection-level,
    // no HTTP response ever received) while other RSS calls in between succeeded fine —
    // a transient blip, not a real block. One retry recovers most of those.
    private static final int RSS_MAX_ATTEMPTS = 2;
    private static final long RSS_RETRY_DELAY_MS = 3000;

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

        for (int attempt = 1; attempt <= RSS_MAX_ATTEMPTS; attempt++) {
            try {
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
                return results;
            } catch (Exception e) {
                if (attempt == RSS_MAX_ATTEMPTS) {
                    log.error("Ошибка HH RSS для '{}': {}: {}", query, e.getClass().getSimpleName(), e.getMessage());
                    return results;
                }
                log.warn("HH RSS для '{}': попытка {} не удалась ({}: {}), повторяем через {}с...",
                    query, attempt, e.getClass().getSimpleName(), e.getMessage(), RSS_RETRY_DELAY_MS / 1000);
                try {
                    Thread.sleep(RSS_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return results;
                }
            }
        }
        return results;
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

        return HttpUtil.readBody(conn, code);
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
