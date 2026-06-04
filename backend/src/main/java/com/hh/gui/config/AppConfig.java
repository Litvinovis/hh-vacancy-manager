package com.hh.gui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

@Configuration
public class AppConfig {

    @Value("${app.data-dir}")
    private String dataDir;

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public SearchProfile searchProfile(
            @Value("${app.search.profiles-dir}") String profilesDir) {
        String profileFile = profilesDir + "/default.yaml";
        try (InputStream is = new FileInputStream(profileFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            return new SearchProfile(data);
        } catch (Exception e) {
            // Fallback to example
            try (InputStream is = new FileInputStream(profileFile + ".example")) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(is);
                return new SearchProfile(data);
            } catch (Exception ex) {
                return new SearchProfile(Map.of());
            }
        }
    }

    public static class SearchProfile {
        private final Map<String, Object> data;

        public SearchProfile(Map<String, Object> data) {
            this.data = data;
        }

        public String getName() {
            return (String) data.getOrDefault("name", "Default");
        }

        public String getCity() {
            return (String) data.getOrDefault("city", "Уфа");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> getSearch() {
            return (Map<String, Object>) data.getOrDefault("search", Map.of());
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> getRemoteSearch() {
            return (Map<String, Object>) data.getOrDefault("remote_search", Map.of());
        }
    }
}
