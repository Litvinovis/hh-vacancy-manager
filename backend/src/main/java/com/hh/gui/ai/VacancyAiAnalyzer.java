package com.hh.gui.ai;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
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
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;

/**
 * AI analyzer for vacancies — calls LLM API directly.
 * Optimized: large batches, rate limiting, exponential backoff retry.
 *
 * Each batch is scored against one SearchJob's criteria (city, districts,
 * skills, salary floor, and free-text ai_notes) — different searches for the
 * same person can weigh "interesting work" very differently (e.g. remote
 * across Russia vs a job near home), so batches are never mixed across jobs.
 */
@Component
public class VacancyAiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VacancyAiAnalyzer.class);
    private static final java.util.Set<String> VALID_VERDICTS = java.util.Set.of("yes", "no", "fraud");
    private static final int MAX_DESCRIPTION_CHARS = 600;
    private static final int FALLBACK_DESCRIPTION_CHARS = 500;

    // hh.ru descriptions are near-universally structured with these section headers
    // (verified against real scraped postings). Duties/requirements decide the score;
    // perks/company-intro are marketing filler the model doesn't need to see.
    private static final Set<String> KEEP_HEADERS = Set.of(
        "обязанност", "чем предстоит заниматься", "что нужно делать", "что будете делать",
        "твои задачи", "ваши задачи", "задачи", "требовани", "кого мы ищем",
        "мы ждём тебя", "мы ждем тебя", "ожидания от кандидата", "тебе предстоит", "вам предстоит");
    private static final Set<String> DROP_HEADERS = Set.of(
        "мы предлагаем", "что мы предлагаем", "услови", "о компании", "о нас",
        "почему мы", "преимуществ", "льгот", "о вакансии", "как откликнуться", "контакты");

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

    /** Check if the rate limit cooldown is active. */
    public boolean isRateLimited() {
        return providerManager.isInCooldown();
    }

    /** Get rate limit cooldown until timestamp (epoch ms). 0 = not limited. */
    public long getRateLimitCooldownUntil() {
        return providerManager.getCooldownUntil();
    }

    /** Get current AI provider state label for UI banner. */
    public String getProviderStateLabel() {
        return providerManager.getStateLabel();
    }

    /** Reset provider to primary (called from settings UI). */
    public void resetProvider() {
        providerManager.reset();
    }

    /**
     * Analyze a batch of vacancies against one search job's criteria.
     * Processes in large chunks with rate limiting and automatic provider fallback.
     */
    public List<AiResult> analyzeBatch(List<Vacancy> vacancies, SearchJob job) {
        if (!providerManager.hasPrimary()) {
            log.warn("AI API key не настроен, пропускаем анализ");
            return List.of();
        }

        if (providerManager.isInCooldown()) {
            log.debug("AI-анализ пропущен — активен период охлаждения");
            return List.of();
        }

        List<AiResult> results = new ArrayList<>();

        for (int i = 0; i < vacancies.size(); i += getBatchSize()) {
            if (providerManager.isInCooldown()) {
                log.info("Остановка AI-анализа — активен период охлаждения после пакета {}", (i / getBatchSize()));
                break;
            }
            int end = Math.min(i + getBatchSize(), vacancies.size());
            List<Vacancy> batch = vacancies.subList(i, end);
            try {
                waitForRateLimit();
                List<AiResult> batchResults = analyzeWithRetry(batch, job, runtimeConfig.getMaxRetries());
                results.addAll(batchResults);

                log.debug("AI-пакет {}/{} готов ({} · {}, {} вакансий, {} результатов) via {}",
                    (i / getBatchSize()) + 1, (vacancies.size() + getBatchSize() - 1) / getBatchSize(),
                    job.personName, job.searchName, batch.size(), batchResults.size(), providerManager.getCurrentProviderName());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Ошибка AI-анализа для пакета {}-{} ({} · {}): {}", i, end, job.personName, job.searchName, e.getMessage());
            }
        }

        return results;
    }

    /** Wait to respect rate limits. */
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
    private List<AiResult> analyzeWithRetry(List<Vacancy> vacancies, SearchJob job, int maxRetries)
            throws Exception {
        int attempt = 0;

        while (true) {
            if (providerManager.isInCooldown()) {
                log.warn("Прерываем попытки — активен cooldown");
                throw new RuntimeException("Cooldown active, aborting");
            }
            try {
                return analyzeChunk(vacancies, job);
            } catch (Exception e) {
                attempt++;
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");

                if (isRateLimit) {
                    String currentProvider = providerManager.getCurrentProviderName();
                    providerManager.switchToFallback();
                    if (providerManager.isInCooldown()) {
                        log.warn("Все провайдеры rate limited. Cooldown. Последний был: {}", currentProvider);
                        throw new RuntimeException("All providers rate limited, entering cooldown");
                    }
                    String nextProvider = providerManager.getCurrentProviderName();
                    log.warn("429 от {}. Переключаемся на провайдера: {}", currentProvider, nextProvider);
                    continue;
                }

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

    private List<AiResult> analyzeChunk(List<Vacancy> vacancies, SearchJob job) throws Exception {
        String prompt = buildPrompt(vacancies, job);
        String response = callLlm(prompt);
        return parseResponse(response, vacancies);
    }

    private String buildPrompt(List<Vacancy> vacancies, SearchJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты — аналитик вакансий. Помогаешь ").append(job.personName)
          .append(" с поиском \"").append(job.searchName).append("\".\n\n");

        sb.append("ПРОФИЛЬ:\n");
        sb.append("Город: ").append(job.city).append("\n");
        if (job.priorityDistricts != null && !job.priorityDistricts.isEmpty()) {
            sb.append("Приоритетные районы (бонус, если есть в адресе): ").append(String.join(", ", job.priorityDistricts)).append("\n");
        }
        if (job.skills != null && !job.skills.isEmpty()) {
            sb.append("Подходящий опыт: ").append(String.join(", ", job.skills)).append("\n");
        }
        if (job.notSuitable != null && !job.notSuitable.isEmpty()) {
            sb.append("НЕ подходит: ").append(String.join(", ", job.notSuitable)).append("\n");
        }
        sb.append("Мин. зарплата: ").append(job.salaryMin).append("₽\n\n");

        sb.append("КАК ОЦЕНИВАТЬ \"ИНТЕРЕСНОСТЬ\" РАБОТЫ (общий ориентир — вес зависит от заметки ниже):\n");
        sb.append("- Интересно: аналитическое мышление, коммуникация, разнообразие задач, непредсказуемый процесс\n");
        sb.append("- Скучно: монотонная обработка однотипных заявок/тикетов, прямые продажи, транскрибация в потоке, жёсткий скрипт\n\n");

        sb.append("ЗАМЕТКА ДЛЯ ЭТОГО ПОИСКА (учитывай в первую очередь, она важнее общих ориентиров выше):\n");
        sb.append(job.aiNotes != null && !job.aiNotes.isBlank() ? job.aiNotes.trim() : "Нет особых заметок.").append("\n\n");

        sb.append("ПРОВЕРКА НА ОБМАН:\n");
        sb.append("- Оцени, не является ли вакансия или компания обманом/скамом\n");
        sb.append("- Завышенная зарплата для простой должности = обман (например, 300000₽ для продавца)\n");
        sb.append("- Сетевые пирамидные продажи (MLM), крипто-схемы, инфо-партнёрства = обман\n");
        sb.append("- Компания без отзывов/сайта/реквизитов с нереалистичными условиями = подозрительно\n");
        sb.append("- \"Доверенный работодатель\" ниже — это подтверждение от hh.ru, весомый плюс к доверию\n");
        sb.append("- Вакансии-скам ставь verdict=\"fraud\" и score=0, но не пропускай их — они остаются в базе, чтобы не анализировать повторно\n\n");

        sb.append("Проанализируй каждую вакансию и верни JSON-массив с полями:\n");
        sb.append("[{\"id\": \"...\", \"score\": 0-100, \"verdict\": \"yes\"|\"no\"|\"fraud\", \"reason\": \"обоснование одной короткой фразой, до 12 слов\"}]\n");
        sb.append("Никакого текста до или после массива. Никаких переносов строк внутри \"reason\".\n\n");

        sb.append("ВАКАНСИИ:\n");
        for (Vacancy v : vacancies) {
            sb.append("---\n");
            sb.append("ID: ").append(v.getHhId()).append("\n");
            sb.append("Название: ").append(v.getTitle()).append("\n");
            sb.append("Работодатель: ").append(v.getCompany());
            sb.append(v.isTrustedEmployer() ? " (доверенный работодатель по hh.ru)\n" : "\n");
            sb.append("Зарплата: ").append(formatSalary(v)).append("\n");
            if (v.getExperience() != null && !v.getExperience().isBlank()) {
                sb.append("Опыт: ").append(v.getExperience()).append("\n");
            }
            if (v.getEmployment() != null && !v.getEmployment().isBlank()) {
                sb.append("Занятость: ").append(v.getEmployment()).append("\n");
            }
            if (v.getKeySkills() != null && !v.getKeySkills().isBlank()) {
                sb.append("Ключевые навыки: ").append(v.getKeySkills()).append("\n");
            }
            sb.append("Адрес: ").append(v.getAddress()).append("\n");
            sb.append("Удалёнка: ").append(v.isRemote() ? "да" : "нет").append("\n");
            sb.append("Описание: ").append(extractKeyInfo(v.getDescription())).append("\n");
        }

        return sb.toString();
    }

    /**
     * Cuts a raw scraped description down to the "обязанности"/"требования"-style
     * sections and drops "мы предлагаем"/"о компании" marketing filler, since a flat
     * character truncation regularly cut off before duties even started (verified
     * against real postings — company intros and perk lists routinely run 500+ chars
     * before the actually decision-relevant text begins). Salary/schedule are already
     * passed as structured fields, so dropping "условия" prose loses nothing there.
     */
    private String extractKeyInfo(String description) {
        if (description == null || description.isBlank()) return "";

        boolean keeping = false;
        boolean anyHeaderFound = false;
        StringBuilder kept = new StringBuilder();

        for (String rawLine : description.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();

            String keepHeader = KEEP_HEADERS.stream().filter(lower::startsWith).findFirst().orElse(null);
            if (keepHeader != null) {
                // Headers are followed by a bullet list on subsequent lines in practice —
                // skip the header line itself rather than trying to salvage inline text
                // after it (that text is often just the rest of the header phrase).
                keeping = true;
                anyHeaderFound = true;
                continue;
            }
            String dropHeader = DROP_HEADERS.stream().filter(lower::startsWith).findFirst().orElse(null);
            if (dropHeader != null) {
                keeping = false;
                anyHeaderFound = true;
                continue;
            }
            if (keeping) kept.append(line).append(" ");
        }

        String result = kept.toString().trim();
        if (!anyHeaderFound || result.length() < 80) {
            // Unstructured posting (short one-liner, no recognizable sections) — fall
            // back to a flat truncation rather than risk keeping nothing useful.
            return description.substring(0, Math.min(FALLBACK_DESCRIPTION_CHARS, description.length()));
        }
        return result.substring(0, Math.min(MAX_DESCRIPTION_CHARS, result.length()));
    }

    private String formatSalary(Vacancy v) {
        boolean hasFrom = v.getSalaryFrom() != null && v.getSalaryFrom() > 0;
        boolean hasTo = v.getSalaryTo() != null && v.getSalaryTo() > 0;
        if (!hasFrom && !hasTo) return "не указана";
        StringBuilder sb = new StringBuilder();
        if (hasFrom) sb.append("от ").append(v.getSalaryFrom());
        if (hasTo) sb.append(" до ").append(v.getSalaryTo());
        if (v.getCurrency() != null) sb.append(" ").append(v.getCurrency());
        if (v.isSalaryGross()) sb.append(" (до вычета налогов)");
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
            "max_tokens", 6000
        );
        byte[] payload = mapper.writeValueAsBytes(requestBody);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();

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

    /**
     * Parses the LLM response. Throws on any malformed/incomplete response (missing
     * choices, no JSON array, truncated array) instead of swallowing the failure —
     * a swallowed failure previously looked like "success, zero results" to the
     * caller, so the batch never retried and those vacancies stayed 'pending'
     * forever. Letting the exception propagate lets analyzeWithRetry's existing
     * backoff/provider-fallback logic actually engage.
     */
    @SuppressWarnings("unchecked")
    private List<AiResult> parseResponse(String json, List<Vacancy> vacancies) throws Exception {
        List<AiResult> results = new ArrayList<>();
        Map<?, ?> response = mapper.readValue(json, Map.class);
        List<?> choices = (List<?>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Ответ AI не содержит choices");
        }

        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        String content = (String) message.get("content");

        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < 0) {
            throw new RuntimeException("JSON-массив не найден в ответе AI: "
                + content.substring(0, Math.min(200, content.length())));
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

            results.add(new AiResult(id, Math.max(0, Math.min(100, score)), verdict, reason));
        }
        return results;
    }

    public record AiResult(String hhId, int score, String verdict, String reason) {}
}
