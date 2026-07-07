package com.hh.gui.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            // Scraping involves a real page load — give it real time, reuse the AI read-timeout knob.
            conn.setReadTimeout(runtimeConfig.getHttpReadTimeoutMs() > 0 ? runtimeConfig.getHttpReadTimeoutMs() : 30000);

            int code = conn.getResponseCode();
            String body;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                body = sb.toString();
            }

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

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }
}
