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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    // Not final — tests point this at a local HttpServer to exercise the circuit
    // breaker without hitting the real hh.ru (see HhApiClientTest).
    private String rssBase = "https://hh.ru/search/vacancy/rss";
    // A 2026-08-04..07 incident showed ~73% of RSS fetches failing (DNS/connection-level,
    // no HTTP response ever received) while other RSS calls in between succeeded fine —
    // a transient blip, not a real block. One retry recovers most of those.
    private static final int RSS_MAX_ATTEMPTS = 2;
    private static final long RSS_RETRY_DELAY_MS = 3000;

    // Circuit breaker: a 5xx from hh.ru means the server itself is struggling, not a
    // per-query fluke (unlike DDoS-Guard 403s or DNS blips, which RSS_MAX_ATTEMPTS
    // already retries) — hammering it from every search on every pipeline tick during
    // a real outage only makes things worse. After CIRCUIT_BREAKER_THRESHOLD consecutive
    // 5xx responses, every query short-circuits (no HTTP call at all) for
    // CIRCUIT_BREAKER_COOLDOWN; the next call after cooldown acts as an implicit
    // half-open trial — success closes the circuit, another 5xx re-opens it.
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final Duration CIRCUIT_BREAKER_COOLDOWN = Duration.ofMinutes(10);

    private final AtomicInteger consecutive5xx = new AtomicInteger(0);
    private volatile Instant circuitOpenedAt;

    private final RuntimeConfig runtimeConfig;

    public HhApiClient(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /** Thrown by httpGet specifically for 5xx — distinguishes "hh.ru is degraded" from
     *  every other failure mode (403, DNS, timeout) that already has its own handling. */
    private static class HhServerErrorException extends RuntimeException {
        final int statusCode;
        HhServerErrorException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private boolean circuitOpen() {
        Instant openedAt = circuitOpenedAt;
        return openedAt != null && Instant.now().isBefore(openedAt.plus(CIRCUIT_BREAKER_COOLDOWN));
    }

    private void recordServerError() {
        int failures = consecutive5xx.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            boolean wasOpen = circuitOpenedAt != null;
            circuitOpenedAt = Instant.now();
            if (!wasOpen) {
                log.warn("HH RSS: цепь разомкнута после {} подряд 5xx от hh.ru — опрос приостановлен на {} мин",
                    failures, CIRCUIT_BREAKER_COOLDOWN.toMinutes());
            }
        }
    }

    private void recordSuccess() {
        if (consecutive5xx.getAndSet(0) >= CIRCUIT_BREAKER_THRESHOLD) {
            log.info("HH RSS: hh.ru снова отвечает нормально — цепь замкнута");
        }
        circuitOpenedAt = null;
    }

    /**
     * Discover vacancy IDs via RSS for a single query.
     * RSS returns up to 20 latest vacancies per query — no pagination.
     */
    public List<Vacancy> fetchRss(String query, int area, String schedule, int salaryMin) {
        List<Vacancy> results = new ArrayList<>();
        if (circuitOpen()) {
            log.debug("HH RSS: цепь разомкнута — пропускаем '{}' до восстановления hh.ru", query);
            return results;
        }
        StringBuilder url = new StringBuilder(rssBase);
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
                recordSuccess();
                return results;
            } catch (Exception e) {
                if (e instanceof HhServerErrorException) {
                    recordServerError();
                }
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
        if (code >= 500) {
            log.warn("HTTP {} (сервер) от {}", code, urlStr);
            throw new HhServerErrorException(code);
        }
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
