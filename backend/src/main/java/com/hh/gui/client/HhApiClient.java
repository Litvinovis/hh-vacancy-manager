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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HH.ru client — uses RSS for collection (works without API token).
 * HH Search API is optional (requires registered app / whitelisted IP).
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
     * Fetch vacancies via RSS for a single query.
     * RSS returns up to 20 latest vacancies per query.
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

            log.info("HH RSS: получено {} вакансий для '{}' (area={}, schedule={})", results.size(), query, area, schedule);
        } catch (Exception e) {
            log.error("Ошибка HH RSS для '{}': {}", query, e.getMessage());
        }
        return results;
    }

    /**
     * Fetch vacancies for multiple queries, deduplicating by HH ID.
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

    private Vacancy parseRssItem(Element item) {
        try {
            Vacancy v = new Vacancy();

            String title = getText(item, "title");
            String link = getText(item, "link");
            String guid = getText(item, "guid");
            String pubDate = getText(item, "pubDate");
            String description = getText(item, "description");

            if (title == null || link == null) return null;

            // Extract HH ID from link
            String hhId = link.replaceAll(".*/vacancy/(\\d+).*", "$1");
            if (hhId.isEmpty()) hhId = guid;

            v.setHhId(hhId);
            v.setTitle(title);
            v.setUrl(link);
            v.setPublishedAt(pubDate);

            // Parse company from description.
            // HH RSS uses NBSP ( ) as a thousands separator and around "от"/"до" —
            // normalize to regular spaces so \s-based regexes below actually match.
            String descClean = description != null
                    ? description.replaceAll("<[^>]+>", "").replace('\u00A0', ' ').trim()
                    : "";
            v.setDescription(descClean.length() > 600 ? descClean.substring(0, 600) : descClean);

            // Extract company
            Pattern companyPattern = Pattern.compile("Вакансия компании:\\s*(.+?)(?:\\n|Создана|$)");
            Matcher m = companyPattern.matcher(descClean);
            if (m.find()) {
                v.setCompany(m.group(1).trim());
            }

            // Extract salary — anchored on the RSS's fixed label to avoid matching
            // unrelated prices elsewhere in the description. Both "от" and "до" are
            // optional since HH lists salary as a range, a floor only, or a cap only.
            int salaryIdx = descClean.indexOf("Предполагаемый уровень месячного дохода");
            if (salaryIdx >= 0) {
                Pattern salaryPattern = Pattern.compile(
                        "(?:от\\s*([\\d\\s]+?))?(?:\\s*до\\s*([\\d\\s]+?))?\\s*([₽$€]|[A-Z]{3})");
                Matcher salaryMatcher = salaryPattern.matcher(descClean.substring(salaryIdx));
                if (salaryMatcher.find() && (salaryMatcher.group(1) != null || salaryMatcher.group(2) != null)) {
                    String from = salaryMatcher.group(1) != null ? salaryMatcher.group(1).replaceAll("\\s", "") : null;
                    String to = salaryMatcher.group(2) != null ? salaryMatcher.group(2).replaceAll("\\s", "") : null;
                    String currency = salaryMatcher.group(3);
                    if (from != null) try { v.setSalaryFrom(Integer.parseInt(from)); } catch (NumberFormatException ignored) {}
                    if (to != null) try { v.setSalaryTo(Integer.parseInt(to)); } catch (NumberFormatException ignored) {}
                    v.setCurrency(currency);
                }
            }

            // Extract address/region — city name only (a single word), not the rest of
            // the line: the previous ".+?(?:\\n|$)" pattern had no real "\n" to stop at
            // (HH RSS puts region and the next field on the same line), so it captured
            // everything up to the end of the description, salary text included.
            Pattern regionPattern = Pattern.compile("Регион:\\s*([А-Яа-яЁё\\-]+)");
            m = regionPattern.matcher(descClean);
            if (m.find()) {
                v.setAddress(m.group(1).trim());
            }

            // District: HH RSS doesn't expose a structured district field, only the city.
            // Best-effort: look for a known Ufa district/microdistrict name anywhere in
            // the description text (this is how the AI prompt already treats "Шакша").
            String district = extractDistrict(descClean);
            if (district != null) {
                v.setDistrict(district);
            }

            v.setStatus("new");
            v.setAiVerdict("pending");
            v.setAiScore(0);
            v.setCreatedAt(Instant.now().toString());

            return v;
        } catch (Exception e) {
            log.warn("Не удалось разобрать RSS-элемент: {}", e.getMessage());
            return null;
        }
    }

    // Ufa's administrative districts + well-known microdistricts. HH RSS has no
    // structured district field, so this is a best-effort text match.
    private static final List<String> DISTRICTS = List.of(
            "Шакша", "Калининский", "Орджоникидзевский", "Кировский", "Ленинский",
            "Октябрьский", "Советский", "Демский");

    private String extractDistrict(String text) {
        if (text == null) return null;
        for (String district : DISTRICTS) {
            if (text.contains(district)) return district;
        }
        return null;
    }

    private String getText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }
}
