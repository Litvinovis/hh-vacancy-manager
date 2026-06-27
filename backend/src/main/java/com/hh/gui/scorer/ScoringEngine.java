package com.hh.gui.scorer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(ScoringEngine.class);

    @Value("${app.scoring.rules-file:config/rules.yaml}")
    private String rulesFile;

    private List<ScoringRule> rules = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadRules();
    }

    public void loadRules() {
        try (InputStream is = new FileInputStream(rulesFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            if (data == null || !data.containsKey("rules")) {
                log.warn("Правила не найдены в {}", rulesFile);
                return;
            }

            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) data.get("rules");
            rules = new ArrayList<>();
            for (Map<String, Object> r : rawRules) {
                String match = (String) r.get("match");
                String field = (String) r.getOrDefault("field", "title|description");
                int score = (int) r.getOrDefault("score", 0);
                String reason = (String) r.getOrDefault("reason", "");
                rules.add(new ScoringRule(match, field, score, reason));
            }
            log.info("Загружено {} правил скоринга из {}", rules.size(), rulesFile);
        } catch (Exception e) {
            log.error("Не удалось загрузить правила скоринга из {}: {}", rulesFile, e.getMessage());
        }
    }

    public ScoringResult score(String title, String description, String address, boolean isRemote) {
        int score = 50;
        List<String> reasons = new ArrayList<>();
        String text = (title + " " + description + " " + address).toLowerCase();

        for (ScoringRule rule : rules) {
            if (rule.matches(text)) {
                score += rule.score();
                if (rule.reason() != null && !rule.reason().isEmpty()) {
                    reasons.add(rule.reason());
                }
            }
        }

        score = Math.max(0, Math.min(100, score));
        String verdict = score >= 50 ? "yes" : "no";
        String reason = reasons.isEmpty() ? "Подходит по профилю" : String.join("; ", reasons);

        return new ScoringResult(score, verdict, reason);
    }

    public record ScoringResult(int score, String verdict, String reason) {}

    private record ScoringRule(String match, String field, int score, String reason) {
        boolean matches(String text) {
            try {
                return Pattern.compile(match, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text).find();
            } catch (Exception e) {
                return text.contains(match.toLowerCase());
            }
        }
    }
}
