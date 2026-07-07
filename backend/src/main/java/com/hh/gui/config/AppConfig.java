package com.hh.gui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads config/profiles/default.yaml: a list of people, each owning one or
 * more independently-configured searches (own queries, own salary/district
 * priorities, own AI scoring notes). Two searches for the same person (e.g.
 * "remote across Russia" vs "near home") can want completely different
 * scoring criteria, so criteria live on the search, not the person.
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public List<PersonConfig> people(@Value("${app.search.profiles-dir}") String profilesDir) {
        String profileFile = profilesDir + "/default.yaml";
        try (InputStream is = new FileInputStream(profileFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root == null || !(root.get("people") instanceof List)) {
                log.warn("В {} не найден ключ 'people' — ни один поиск не настроен", profileFile);
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawPeople = (List<Map<String, Object>>) root.get("people");
            List<PersonConfig> people = rawPeople.stream().map(PersonConfig::new).collect(Collectors.toList());
            int totalSearches = people.stream().mapToInt(p -> p.searches.size()).sum();
            log.info("Загружено {} человек(а), {} поисков из {}", people.size(), totalSearches, profileFile);
            return people;
        } catch (Exception e) {
            log.error("Не удалось загрузить {}: {}", profileFile, e.getMessage());
            return List.of();
        }
    }

    /** One person (family member) and all of their configured searches. */
    public static class PersonConfig {
        public final String name;
        public final String city;
        public final List<SearchConfig> searches;

        @SuppressWarnings("unchecked")
        public PersonConfig(Map<String, Object> data) {
            this.name = (String) data.getOrDefault("name", "default");
            this.city = (String) data.getOrDefault("city", "");
            Object searchesObj = data.get("searches");
            if (searchesObj instanceof List) {
                this.searches = ((List<Map<String, Object>>) searchesObj).stream()
                    .map(SearchConfig::new)
                    .collect(Collectors.toList());
            } else {
                this.searches = List.of();
            }
        }
    }

    /**
     * One independently-scored search: its own query list, its own priorities,
     * and its own free-text notes for the AI prompt (e.g. "интересность задач
     * важнее близости" vs "близость важнее интересности задач").
     */
    public static class SearchConfig {
        public final String name;
        public final List<String> queries;
        public final int area;
        public final String schedule;
        public final int salaryMin;
        public final List<String> excludeWords;
        public final List<String> priorityDistricts;
        public final List<String> skills;
        public final List<String> notSuitable;
        public final String aiNotes;

        @SuppressWarnings("unchecked")
        public SearchConfig(Map<String, Object> data) {
            this.name = (String) data.getOrDefault("name", "default");
            this.queries = (List<String>) data.getOrDefault("queries", List.of());
            this.area = (int) data.getOrDefault("area", 99);
            this.schedule = (String) data.getOrDefault("schedule", "");
            this.salaryMin = (int) data.getOrDefault("salary_min", 0);
            this.excludeWords = (List<String>) data.getOrDefault("exclude_words", List.of());
            this.priorityDistricts = (List<String>) data.getOrDefault("priority_districts", List.of());
            this.skills = (List<String>) data.getOrDefault("skills", List.of());
            this.notSuitable = (List<String>) data.getOrDefault("not_suitable", List.of());
            this.aiNotes = (String) data.getOrDefault("ai_notes", "");
        }

        /** @return true if this is a remote search (schedule=remote or area=113/Russia-wide) */
        public boolean isRemote() {
            return "remote".equalsIgnoreCase(schedule) || area == 113;
        }
    }
}
