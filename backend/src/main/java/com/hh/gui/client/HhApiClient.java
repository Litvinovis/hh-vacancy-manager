package com.hh.gui.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hh.gui.model.Vacancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * HH.ru Search API client with pagination.
 * Replaces RSS parser — fetches ALL vacancies matching query, not just 20 latest.
 */
@Component
public class HhApiClient {

    private static final Logger log = LoggerFactory.getLogger(HhApiClient.class);
    private static final String API_BASE = "https://api.hh.ru/vacancies";
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 20;
    private static final int REQUEST_DELAY_MS = 1500;

    private final ObjectMapper mapper;

    public HhApiClient() {
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Fetch all vacancies for a query with full pagination.
     */
    public List<Vacancy> fetchAll(String query, int area, String schedule, int salaryMin) {
        List<Vacancy> all = new ArrayList<>();
        int page = 0;
        int totalPages = 1;
        int totalFound = 0;

        while (page < totalPages && page < MAX_PAGES) {
            try {
                String url = buildUrl(query, area, schedule, salaryMin, page);
                log.debug("HH API: page {}/{} — {}", page + 1, totalPages, url);

                String json = httpGet(url);
                if (json == null) break;

                Map<String, Object> response = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

                if (page == 0) {
                    totalFound = getInt(response, "found");
                    totalPages = getInt(response, "pages");
                    log.info("HH API: '{}' — found {} vacancies, {} pages", query, totalFound, totalPages);
                }

                List<Map<String, Object>> items = getList(response, "items");
                if (items == null || items.isEmpty()) break;

                for (Map<String, Object> item : items) {
                    Vacancy v = parseVacancy(item);
                    if (v != null) {
                        v.setSource("hh");
                        v.setSourceQuery(query);
                        v.setRemote("remote".equals(schedule));
                        all.add(v);
                    }
                }

                page++;
                if (page < totalPages) {
                    Thread.sleep(REQUEST_DELAY_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("HH API error on page {}: {}", page, e.getMessage());
                break;
            }
        }

        log.info("HH API: fetched {} vacancies for '{}' (area={}, schedule={})", all.size(), query, area, schedule);
        return all;
    }

    /**
     * Fetch vacancies for multiple queries, deduplicating by HH ID.
     */
    public List<Vacancy> fetchMultiple(List<String> queries, int area, String schedule, int salaryMin) {
        Map<String, Vacancy> seen = new LinkedHashMap<>();
        for (String query : queries) {
            List<Vacancy> batch = fetchAll(query, area, schedule, salaryMin);
            for (Vacancy v : batch) {
                seen.putIfAbsent(v.getHhId(), v);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private String buildUrl(String query, int area, String schedule, int salaryMin, int page) {
        StringBuilder sb = new StringBuilder(API_BASE);
        sb.append("?text=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        sb.append("&area=").append(area);
        sb.append("&per_page=").append(PER_PAGE);
        sb.append("&page=").append(page);
        if (schedule != null && !schedule.isEmpty()) {
            sb.append("&schedule=").append(schedule);
        }
        if (salaryMin > 0) {
            sb.append("&salary=").append(salaryMin);
        }
        return sb.toString();
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "HHVacancyManager/1.0 (igrlitvinv11022@gmail.com)");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        int code = conn.getResponseCode();
        if (code != 200) {
            log.warn("HTTP {} from {}", code, urlStr);
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private Vacancy parseVacancy(Map<String, Object> item) {
        try {
            Vacancy v = new Vacancy();

            String id = (String) item.get("id");
            if (id == null || id.isEmpty()) return null;
            v.setHhId(id);
            v.setTitle((String) item.getOrDefault("name", ""));

            Map<String, Object> employer = (Map<String, Object>) item.get("employer");
            if (employer != null) {
                v.setCompany((String) employer.getOrDefault("name", ""));
            }

            Map<String, Object> salary = (Map<String, Object>) item.get("salary");
            if (salary != null) {
                Object from = salary.get("from");
                Object to = salary.get("to");
                if (from instanceof Number) v.setSalaryFrom(((Number) from).intValue());
                if (to instanceof Number) v.setSalaryTo(((Number) to).intValue());
                v.setCurrency((String) salary.getOrDefault("currency", "RUR"));
            }

            Map<String, Object> address = (Map<String, Object>) item.get("address");
            if (address != null) {
                v.setAddress((String) address.getOrDefault("raw", ""));
            }

            v.setUrl((String) item.getOrDefault("alternate_url", "https://hh.ru/vacancy/" + id));
            v.setPublishedAt((String) item.getOrDefault("published_at", ""));

            Map<String, Object> snippet = (Map<String, Object>) item.get("snippet");
            if (snippet != null) {
                String req = (String) snippet.getOrDefault("requirement", "");
                String resp = (String) snippet.getOrDefault("responsibility", "");
                String desc = (req != null ? req : "") + "\n" + (resp != null ? resp : "");
                v.setDescription(desc.length() > 600 ? desc.substring(0, 600) : desc);
            }

            v.setStatus("new");
            v.setAiVerdict("pending");
            v.setAiScore(0);
            v.setCreatedAt(Instant.now().toString());

            return v;
        } catch (Exception e) {
            log.warn("Failed to parse vacancy: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) return (List<Map<String, Object>>) val;
        return null;
    }
}
