package com.hh.gui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${app.data-dir}")
    private String dataDir;

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public SearchProfile searchProfile(
            @Value("${app.search.profiles-dir}") String profilesDir,
            @Value("${app.pipeline.profile:mom}") String profileName) {
        String profileFile = profilesDir + "/default.yaml";
        try (InputStream is = new FileInputStream(profileFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root != null && root.containsKey("profiles")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");
                if (profiles != null && profiles.containsKey(profileName)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profile = (Map<String, Object>) profiles.get(profileName);
                    log.info("Загружен профиль '{}' из {}", profileName, profileFile);
                    return new SearchProfile(profile);
                }
            }
            log.warn("Профиль '{}' не найден в {}, используем пустой", profileName, profileFile);
            return new SearchProfile(Map.of());
        } catch (Exception e) {
            log.error("Не удалось загрузить профиль: {}", e.getMessage());
            return new SearchProfile(Map.of());
        }
    }

    /**
     * Single search entry from config.
     */
    public static class SearchEntry {
        public String name;
        public List<String> queries;
        public int area;
        public String schedule;
        public int salaryMin;
        public List<String> excludeWords;

        @SuppressWarnings("unchecked")
        public SearchEntry(Map<String, Object> data) {
            this.name = (String) data.getOrDefault("name", "default");
            this.queries = (List<String>) data.getOrDefault("queries", List.of());
            this.area = (int) data.getOrDefault("area", 99);
            this.schedule = (String) data.getOrDefault("schedule", "");
            this.salaryMin = (int) data.getOrDefault("salary_min", 0);
            this.excludeWords = (List<String>) data.getOrDefault("exclude_words", List.of());
        }

        /** @return true if this is a remote search (schedule=remote or area=113) */
        public boolean isRemote() {
            return "remote".equalsIgnoreCase(schedule) || area == 113;
        }
    }

    public static class SearchProfile {
        private final Map<String, Object> data;
        private final List<SearchEntry> searches;

        @SuppressWarnings("unchecked")
        public SearchProfile(Map<String, Object> data) {
            this.data = data != null ? data : Map.of();
            this.searches = parseSearches();
        }

        /**
         * Parse searches list from config.
         * New format: searches: [{name, queries, area, ...}, ...]
         * Legacy fallback: search: {...} + remote_search: {...}
         */
        @SuppressWarnings("unchecked")
        private List<SearchEntry> parseSearches() {
            // New format: searches list
            Object searchesObj = data.get("searches");
            if (searchesObj instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) searchesObj;
                if (!list.isEmpty()) {
                    return list.stream()
                            .map(SearchEntry::new)
                            .collect(Collectors.toList());
                }
            }

            // Legacy fallback: search + remote_search
            List<SearchEntry> legacy = new ArrayList<>();
            Object searchObj = data.get("search");
            if (searchObj instanceof Map) {
                legacy.add(new SearchEntry((Map<String, Object>) searchObj));
            }
            Object remoteObj = data.get("remote_search");
            if (remoteObj instanceof Map) {
                Map<String, Object> remote = (Map<String, Object>) remoteObj;
                if (Boolean.TRUE.equals(remote.get("enabled"))) {
                    legacy.add(new SearchEntry(remote));
                }
            }
            return legacy;
        }

        public Map<String, Object> getData() { return data; }

        public String getName() {
            return (String) data.getOrDefault("name", "Default");
        }

        public String getCity() {
            return (String) data.getOrDefault("city", "Уфа");
        }

        /**
         * Get all search entries (new format).
         */
        public List<SearchEntry> getSearches() {
            return searches;
        }

        /**
         * Legacy: get first (local) search. For backward compatibility.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> getSearch() {
            return (Map<String, Object>) data.getOrDefault("search", Map.of());
        }

        /**
         * Legacy: get remote search. For backward compatibility.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> getRemoteSearch() {
            return (Map<String, Object>) data.getOrDefault("remote_search", Map.of());
        }
    }
}
