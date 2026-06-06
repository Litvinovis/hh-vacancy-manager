package com.hh.gui.ai;

import com.hh.gui.model.Vacancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * AI analyzer for vacancies — calls LLM API directly.
 * Replaces Hermes cron AI analysis for the Java project.
 */
@Component
public class VacancyAiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VacancyAiAnalyzer.class);

    @Value("${app.ai.api-url:https://openrouter.ai/api/v1/chat/completions}")
    private String apiUrl;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${app.ai.model:openrouter/auto}")
    private String model;

    @Value("${app.ai.batch-size:5}")
    private int batchSize;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Analyze a batch of vacancies against the profile.
     * Returns list of results with score, verdict, reason.
     */
    public List<AiResult> analyzeBatch(List<Vacancy> vacancies, SearchProfile profile) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("AI API key not configured, skipping AI analysis");
            return List.of();
        }

        List<AiResult> results = new ArrayList<>();

        // Process in batches
        for (int i = 0; i < vacancies.size(); i += batchSize) {
            int end = Math.min(i + batchSize, vacancies.size());
            List<Vacancy> batch = vacancies.subList(i, end);
            try {
                List<AiResult> batchResults = analyzeChunk(batch, profile);
                results.addAll(batchResults);
                Thread.sleep(500); // rate limiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("AI analysis error: {}", e.getMessage());
            }
        }

        return results;
    }

    private List<AiResult> analyzeChunk(List<Vacancy> vacancies, SearchProfile profile) throws Exception {
        String prompt = buildPrompt(vacancies, profile);
        String response = callLlm(prompt);
        return parseResponse(response, vacancies);
    }

    private String buildPrompt(List<Vacancy> vacancies, SearchProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты — аналитик вакансий. Профиль ищущего работу:\n\n");
        sb.append("Город: ").append(profile.city).append("\n");
        sb.append("Приоритетные районы: ").append(String.join(", ", profile.priorityDistricts)).append("\n");
        sb.append("Опыт: ").append(String.join(", ", profile.skills)).append("\n");
        sb.append("НЕ подходит: ").append(String.join(", ", profile.notSuitable)).append("\n");
        sb.append("Мин. зарплата: ").append(profile.salaryMin).append("₽\n");
        sb.append("График: ").append(profile.schedule).append("\n\n");

        sb.append("КРИТЕРИИ ОЦЕНКИ:\n");
        sb.append("- Зарплата >= ").append(profile.salaryMin).append("₽\n");
        sb.append("- Не физический труд / производство / завод / склад\n");
        sb.append("- Не супермаркет / гипермаркет\n");
        sb.append("- Не кол-центр / телефонная поддержка / холодные звонки\n");
        sb.append("- Не вахта / курьер / водитель\n");
        sb.append("- Не требует продвинутых ПК-навыков (программист, DevOps, 1С)\n");
        sb.append("- Удалёнка или город ").append(profile.city).append("\n");
        sb.append("- Соответствие опыту: ").append(String.join(", ", profile.skills)).append("\n");
        sb.append("- Шакша в адресе = бонус\n\n");

        sb.append("Проанализируй каждую вакансию и верни JSON-массив с полями:\n");
        sb.append("[{\"id\": \"...\", \"score\": 0-100, \"verdict\": \"yes\" или \"no\", \"reason\": \"краткое обоснование\"}]\n\n");

        sb.append("ВАКАНСИИ:\n");
        for (Vacancy v : vacancies) {
            sb.append("---\n");
            sb.append("ID: ").append(v.getHhId()).append("\n");
            sb.append("Название: ").append(v.getTitle()).append("\n");
            sb.append("Компания: ").append(v.getCompany()).append("\n");
            sb.append("Зарплата: ");
            if (v.getSalaryFrom() != null && v.getSalaryFrom() > 0) {
                sb.append("от ").append(v.getSalaryFrom());
            }
            if (v.getSalaryTo() != null && v.getSalaryTo() > 0) {
                sb.append(" до ").append(v.getSalaryTo());
            }
            if (v.getCurrency() != null) sb.append(" ").append(v.getCurrency());
            if (v.getSalaryFrom() == null || v.getSalaryFrom() == 0) sb.append("не указана");
            sb.append("\n");
            sb.append("Адрес: ").append(v.getAddress()).append("\n");
            sb.append("Удалёнка: ").append(v.isRemote() ? "да" : "нет").append("\n");
            sb.append("Описание: ").append(v.getDescription()).append("\n");
        }

        return sb.toString();
    }

    private String callLlm(String prompt) throws Exception {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);

        // Build JSON payload
        String escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String messages = "[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}]";
        String payload = "{\"model\":\"" + model + "\"," +
            "\"messages\":" + messages + "," +
            "\"temperature\":0.3," +
            "\"max_tokens\":2000}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (code >= 400) {
                log.error("LLM API error {}: {}", code, sb);
                throw new RuntimeException("LLM API returned " + code);
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private List<AiResult> parseResponse(String json, List<Vacancy> vacancies) {
        List<AiResult> results = new ArrayList<>();
        try {
            Map<?, ?> response = mapper.readValue(json, Map.class);
            List<?> choices = (List<?>) response.get("choices");
            if (choices == null || choices.isEmpty()) return results;

            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            String content = (String) message.get("content");

            // Extract JSON from response
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end < 0) {
                log.warn("No JSON array in AI response: {}", content.substring(0, Math.min(200, content.length())));
                return results;
            }

            String jsonArray = content.substring(start, end + 1);
            List<Map<String, Object>> items = mapper.readValue(jsonArray, List.class);

            for (Map<String, Object> item : items) {
                String id = (String) item.get("id");
                int score = ((Number) item.getOrDefault("score", 0)).intValue();
                String verdict = (String) item.getOrDefault("verdict", "no");
                String reason = (String) item.getOrDefault("reason", "");

                AiResult r = new AiResult(id, Math.max(0, Math.min(100, score)),
                    "yes".equals(verdict) ? "yes" : "no", reason);
                results.add(r);
            }
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", e.getMessage());
        }
        return results;
    }

    public record AiResult(String hhId, int score, String verdict, String reason) {}

    /**
     * Profile data for AI analysis.
     */
    public static class SearchProfile {
        public String city;
        public List<String> priorityDistricts;
        public List<String> skills;
        public List<String> notSuitable;
        public int salaryMin;
        public String schedule;

        public SearchProfile() {}

        public SearchProfile(String city, List<String> priorityDistricts, List<String> skills,
                             List<String> notSuitable, int salaryMin, String schedule) {
            this.city = city;
            this.priorityDistricts = priorityDistricts;
            this.skills = skills;
            this.notSuitable = notSuitable;
            this.salaryMin = salaryMin;
            this.schedule = schedule;
        }
    }
}
