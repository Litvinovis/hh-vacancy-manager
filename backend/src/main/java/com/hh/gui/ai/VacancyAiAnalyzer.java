package com.hh.gui.ai;

import com.hh.gui.config.RuntimeConfig;
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
 * Optimized: large batches, rate limiting, exponential backoff retry.
 */
@Component
public class VacancyAiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VacancyAiAnalyzer.class);
    private static final java.util.Set<String> VALID_VERDICTS = java.util.Set.of("yes", "no", "fraud");

    @Value("${app.ai.batch-size:10}")
    private int batchSizeDefault;

    private final RuntimeConfig runtimeConfig;
    private final AiProviderManager providerManager;
    private final AiMetrics metrics;

    // Rate limiter: free models need ~10-15s between requests to avoid 429
    private long lastRequestTime = 0;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public VacancyAiAnalyzer(RuntimeConfig runtimeConfig, AiProviderManager providerManager, AiMetrics metrics) {
        this.runtimeConfig = runtimeConfig;
        this.providerManager = providerManager;
        this.metrics = metrics;
    }

    private int getBatchSize() {
        return runtimeConfig.getAiBatchSize() > 0 ? runtimeConfig.getAiBatchSize() : batchSizeDefault;
    }

    /**
     * Check if the rate limit cooldown is active.
     */
    public boolean isRateLimited() {
        return providerManager.isInCooldown();
    }

    /**
     * Get rate limit cooldown until timestamp (epoch ms). 0 = not limited.
     */
    public long getRateLimitCooldownUntil() {
        return providerManager.getCooldownUntil();
    }

    /**
     * Get current AI provider state label for UI banner.
     */
    public String getProviderStateLabel() {
        return providerManager.getStateLabel();
    }

    /**
     * Reset provider to primary (called from settings UI).
     */
    public void resetProvider() {
        providerManager.reset();
    }

    /**
     * Analyze a batch of vacancies against the profile.
     * Processes in large chunks with rate limiting and automatic provider fallback.
     */
    public List<AiResult> analyzeBatch(List<Vacancy> vacancies, SearchProfile profile) {
        if (!providerManager.hasPrimary()) {
            log.warn("AI API key не настроен, пропускаем анализ");
            return List.of();
        }

        // Check rate limit cooldown — skip all analysis if active
        if (providerManager.isInCooldown()) {
            log.debug("AI-анализ пропущен — активен период охлаждения");
            return List.of();
        }

        List<AiResult> results = new ArrayList<>();

        for (int i = 0; i < vacancies.size(); i += getBatchSize()) {
            // Check cooldown before each batch (may have been set by a previous batch failure)
            if (providerManager.isInCooldown()) {
                log.info("Остановка AI-анализа — активен период охлаждения после пакета {}", (i / getBatchSize()));
                break;
            }
            int end = Math.min(i + getBatchSize(), vacancies.size());
            List<Vacancy> batch = vacancies.subList(i, end);
            try {
                // Rate limiting
                waitForRateLimit();

                // Retry with exponential backoff (may switch providers on 429)
                List<AiResult> batchResults = analyzeWithRetry(batch, profile, runtimeConfig.getMaxRetries());
                results.addAll(batchResults);

                log.debug("AI-пакет {}/{} готов ({} вакансий, {} результатов) via {}",
                    (i / getBatchSize()) + 1, (vacancies.size() + getBatchSize() - 1) / getBatchSize(),
                    batch.size(), batchResults.size(), providerManager.getCurrentProviderName());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Ошибка AI-анализа для пакета {}-{}: {}", i, end, e.getMessage());
            }
        }

        return results;
    }

    /**
     * Wait to respect rate limits.
     */
    private synchronized void waitForRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        long minInterval = runtimeConfig.getAiRequestDelayMs();
        if (elapsed < minInterval) {
            Thread.sleep(minInterval - elapsed);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * Analyze with exponential backoff retry.
     * On 429 (rate limit): switch to the next provider in the chain.
     * If all providers are rate limited, enter cooldown.
     */
    private List<AiResult> analyzeWithRetry(List<Vacancy> vacancies, SearchProfile profile, int maxRetries)
            throws Exception {
        int attempt = 0;

        while (true) {
            // Stop retrying if cooldown active
            if (providerManager.isInCooldown()) {
                log.warn("Прерываем попытки — активен cooldown");
                throw new RuntimeException("Cooldown active, aborting");
            }
            try {
                return analyzeChunk(vacancies, profile);
            } catch (Exception e) {
                attempt++;
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");

                // On 429: try next provider (may enter cooldown if last in chain)
                if (isRateLimit) {
                    String currentProvider = providerManager.getCurrentProviderName();
                    providerManager.switchToFallback(); // advances index or enters cooldown
                    if (providerManager.isInCooldown()) {
                        log.warn("Все провайдеры rate limited. Cooldown. Последний был: {}", currentProvider);
                        throw new RuntimeException("All providers rate limited, entering cooldown");
                    }
                    String nextProvider = providerManager.getCurrentProviderName();
                    log.warn("429 от {}. Переключаемся на провайдера: {}", currentProvider, nextProvider);
                    continue; // retry immediately with new provider
                }

                // Standard backoff for transient errors (timeout, 5xx)
                if (attempt >= maxRetries) {
                    log.error("AI-анализ не удался после {} попыток: {}", maxRetries, e.getMessage());
                    throw e;
                }

                long backoff = (long) Math.pow(2, attempt) * 7500;
                log.warn("Попытка AI-анализа {}/{} не удалась ({}), повторяем через {}с...",
                    attempt, maxRetries, e.getMessage(), backoff / 1000);
                Thread.sleep(backoff);
            }
        }
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

        sb.append("УДАЛЁННАЯ РАБОТА:\n");
        sb.append("- Если вакансия предлагает удалённый формат — оценивай её ВЫШЕ, это приоритет\n");
        sb.append("- Но удалёнка должна быть ИНТЕРЕСНОЙ, а не рутинной\n\n");

        sb.append("ЧТО СЧИТАТЬ ИНТЕРЕСНОЙ РАБОТОЙ (высокий балл):\n");
        sb.append("- Задачи требуют аналитического мышления, коммуникации, разнообразия\n");
        sb.append("- Непредсказуемый рабочий процесс, не монотонная конвейерная работа\n");
        sb.append("- Нестандартные должности: координатор проектов, специалист по обучению, ");
        sb.append("куратор, модератор контента, оператор на расшифровке, ассистент с разными ");
        sb.append("задачами, специалист по вводу и обработке данных, виртуальный помощник\n\n");

        sb.append("ЧТО СЧИТАТЬ НЕИНТЕРЕСНОЙ РАБОТОЙ (низкий балл даже при удалёнке):\n");
        sb.append("- Поддержка/консультирование банковских клиентов по типовым вопросам\n");
        sb.append("- Монотонная обработка однотипных заявок, писем, тикетов без разнообразия задач\n");
        sb.append("- Прямые продажи, active sales, впаривание услуг\n");
        sb.append("- Транскрибация/расшифровка аудио «в потоке», копипаст данных\n");
        sb.append("- Работа по скриптам с жёстким регламентом и сквозной контролем\n\n");

        sb.append("ФОРМУЛА ОЦЕНКИ УДАЛЁНКИ:\n");
        sb.append("- Удалёнка + интересная работа = score 70-100 (высокий приоритет)\n");
        sb.append("- Удалёнка + скучная/монотонная работа = score 30-50 (не приоритет)\n");
        sb.append("- Офис/гибрид + интересная = score 50-70\n");
        sb.append("- Офис/гибрид + скучная = score 20-40\n");
        sb.append("- Лайфхак: даже обычная должность с необычным описанием или компанией может быть интересной\n\n");

        sb.append("ПРОВЕРКА НА ОБМАН:\n");
        sb.append("- Оцени, не является ли вакансия или компанией обманом/скамом\n");
        sb.append("- Завышенная зарплата для простой должности = обман (например, 300000₽ для продавца)\n");
        sb.append("- Сетевые пирамидные продажи (MLM), крипто-схемы, инфо-партнёрства = обман\n");
        sb.append("- Компания без отзывов/сайта/реквизитов с нереалистичными условиями = подозрительно\n");
        sb.append("- Вакансии-скам ставь verdict=\"fraud\" и score=0, но оставляй в базе (не удаляй)\n");
        sb.append("- Это нужно, чтобы не анализировать одну и ту же мошенническую вакансию повторно\n\n");

        sb.append("Проанализируй каждую вакансию и верни JSON-массив с полями:\n");
        sb.append("[{\"id\": \"...\", \"score\": 0-100, \"verdict\": \"yes\", \"no\" или \"fraud\", \"reason\": \"краткое обоснование\"}]\n\n");

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
            sb.append("Описание: ").append(v.getDescription() != null ? v.getDescription().substring(0, Math.min(500, v.getDescription().length())) : "").append("\n");
        }

        return sb.toString();
    }

    private String callLlm(String prompt) throws Exception {
        String url = providerManager.getCurrentUrl();
        String key = providerManager.getCurrentKey();
        String model = providerManager.getCurrentModel();
        String provider = providerManager.getCurrentProviderName();

        if (key == null || key.isEmpty()) {
            throw new RuntimeException("AI API key not configured for " + provider);
        }

        URL apiUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setConnectTimeout(runtimeConfig.getHttpConnectTimeoutMs());
        conn.setReadTimeout(runtimeConfig.getHttpReadTimeoutMs());
        conn.setDoOutput(true);

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "temperature", 0.3,
            "max_tokens", 4000
        );
        byte[] payload = mapper.writeValueAsBytes(requestBody);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();

        // Record metrics
        metrics.recordRequest(provider);
        if (code == 429) {
            metrics.recordRateLimit(provider);
        } else if (code >= 400) {
            metrics.recordError(provider, code);
        }

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
                log.error("Ошибка LLM API {} ({}): {}", code, provider, sb);
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

            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end < 0) {
                log.warn("JSON-массив не найден в ответе AI: {}", content.substring(0, Math.min(200, content.length())));
                return results;
            }

            String jsonArray = content.substring(start, end + 1);
            List<Map<String, Object>> items = mapper.readValue(jsonArray, List.class);

            for (Map<String, Object> item : items) {
                String id = (String) item.get("id");
                int score = ((Number) item.getOrDefault("score", 0)).intValue();
                String verdict = (String) item.getOrDefault("verdict", "no");
                String reason = (String) item.getOrDefault("reason", "");

                if (!VALID_VERDICTS.contains(verdict)) {
                    log.warn("AI вернул неожиданный verdict '{}' для вакансии {}, приводим к 'no'", verdict, id);
                    verdict = "no";
                }

                AiResult r = new AiResult(id, Math.max(0, Math.min(100, score)),
                    verdict, reason);
                results.add(r);
            }
        } catch (Exception e) {
            log.error("Не удалось разобрать ответ AI: {}", e.getMessage());
        }
        return results;
    }

    public record AiResult(String hhId, int score, String verdict, String reason) {}

    public static class SearchProfile {
        public String city;
        public List<String> priorityDistricts;
        public List<String> skills;
        public List<String> notSuitable;
        public int salaryMin;
        public String schedule;

    public static SearchProfile defaultProfile() {
        SearchProfile p = new SearchProfile();
        p.city = "Уфа";
        p.priorityDistricts = List.of("Шакша", "Калининский");
        p.skills = List.of("Работа с клиентами", "Касса", "Консультирование");
        p.notSuitable = List.of("Физический труд", "Кол-центр", "Вахта", "Склад",
                                 "Производство", "Супермаркет", "Телефонные продажи");
        p.salaryMin = 40000;
        return p;
    }
}
}
