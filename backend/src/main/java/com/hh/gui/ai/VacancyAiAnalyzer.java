package com.hh.gui.ai;

import com.hh.gui.client.ScraperClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.util.DedupKeys;
import com.hh.gui.util.HttpUtil;
import com.hh.gui.util.SalaryFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

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

    /**
     * Cheap pre-scrape filter for URL-search cards (title/employer/salary/address only —
     * no description, since nothing has been scraped yet). Decides which candidates are
     * worth a full scrape + real AI analysis at all, so a 1000-vacancy URL search doesn't
     * pay a full browser scrape for every hit. Fails OPEN on any error (missing provider,
     * cooldown, malformed response, exception) — every hit passes through unfiltered
     * rather than risk silently dropping good candidates because of a transient AI issue;
     * the real analyzeBatch() after scraping remains the authoritative filter.
     */
    public List<AiResult> prescreenHits(List<ScraperClient.SearchHit> hits, SearchJob job) {
        if (hits.isEmpty()) return List.of();
        if (!providerManager.hasPrimary() || providerManager.isInCooldown()) {
            return passAllOpen(hits, "AI недоступен — прескрининг пропущен");
        }

        // Clone collapsing: the same real vacancy posted per city floods a listing with
        // dozens of near-identical cards (measured live: one T-Bank posting appeared as
        // 87 cards, each burning a prescreen slot for the same inevitable answer). Only
        // one representative per group goes to the LLM; its verdict fans out to the rest.
        // Grouping deliberately allows an empty employer (unlike DedupKeys.compute) —
        // RSS candidates carry a title only, and the prescreen's own input for them IS
        // just the title, so identical titles get identical answers by construction.
        Map<String, List<ScraperClient.SearchHit>> groups = new LinkedHashMap<>();
        for (ScraperClient.SearchHit h : hits) {
            String key = DedupKeys.normalize(h.title()) + "|" + DedupKeys.normalize(h.employerName());
            // No usable title — never collapse, judge individually.
            groups.computeIfAbsent("|".equals(key) ? "raw:" + h.hhId() : key, k -> new ArrayList<>()).add(h);
        }
        List<ScraperClient.SearchHit> representatives = groups.values().stream().map(g -> g.get(0)).toList();
        if (representatives.size() < hits.size()) {
            log.info("Прескрининг ({} · {}): {} карточек схлопнуто в {} уникальных по названию+работодателю",
                job.personName, job.searchName, hits.size(), representatives.size());
        }

        List<AiResult> repResults = new ArrayList<>();
        int batchSize = runtimeConfig.getCardPrescreenBatchSize() > 0 ? runtimeConfig.getCardPrescreenBatchSize() : 30;
        for (int i = 0; i < representatives.size(); i += batchSize) {
            List<ScraperClient.SearchHit> batch = representatives.subList(i, Math.min(i + batchSize, representatives.size()));
            if (providerManager.isInCooldown()) {
                repResults.addAll(passAllOpen(batch, "Cooldown — прескрининг пропущен"));
                continue;
            }
            try {
                repResults.addAll(prescreenBatchWithRetry(batch, job));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                repResults.addAll(passAllOpen(batch, "Прервано — прескрининг пропущен"));
            } catch (Exception e) {
                log.warn("Прескрининг карточек не удался ({} · {}): {} — пропускаем фильтр для этой пачки", job.personName, job.searchName, e.getMessage());
                repResults.addAll(passAllOpen(batch, "Ошибка прескрининга — пропущен"));
            }
        }

        Map<String, AiResult> byRepId = new HashMap<>();
        for (AiResult r : repResults) byRepId.put(r.hhId(), r);
        List<AiResult> results = new ArrayList<>();
        for (List<ScraperClient.SearchHit> group : groups.values()) {
            AiResult rep = byRepId.get(group.get(0).hhId());
            for (ScraperClient.SearchHit member : group) {
                if (rep == null) continue; // representative omitted from the answer — member stays unfiltered (fail open)
                results.add(member.hhId().equals(rep.hhId())
                    ? rep
                    : new AiResult(member.hhId(), rep.score(), rep.verdict(), rep.reason()));
            }
        }
        return results;
    }

    private List<AiResult> passAllOpen(List<ScraperClient.SearchHit> hits, String reason) {
        return hits.stream().map(h -> new AiResult(h.hhId(), 50, "yes", reason)).toList();
    }

    /**
     * One prescreen LLM round-trip with a single retry. A live run on the
     * "openrouter/free" router showed why both halves matter: it routes each request
     * to an arbitrary free model, and reasoning models spend most of the completion
     * budget thinking out loud — with the old cap of min(3000, 200+60×N) (2000 tokens
     * for a 30-card batch) the response got cut off mid-reasoning, before any JSON,
     * on every batch. The generous cap is headroom, not extra spend (max_tokens only
     * bounds generation), and the retry usually lands on a different model.
     */
    protected List<AiResult> prescreenBatchWithRetry(List<ScraperClient.SearchHit> batch, SearchJob job) throws Exception {
        String prompt = buildPrescreenPrompt(batch, job);
        int maxTokens = Math.min(6000, 3000 + 60 * batch.size());
        for (int attempt = 1; ; attempt++) {
            waitForRateLimit();
            try {
                return parseResponse(callLlm(prompt, maxTokens), List.of());
            } catch (Exception e) {
                if (attempt >= 2) throw e;
                log.warn("Прескрининг: попытка {} не удалась ({}), повторяем", attempt, e.getMessage());
            }
        }
    }

    private String buildPrescreenPrompt(List<ScraperClient.SearchHit> hits, SearchJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты быстро отбираешь вакансии по краткой карточке из выдачи hh.ru — до открытия полного описания.\n\n");
        sb.append("ЧТО ИЩЕМ (\"").append(job.searchName).append("\"):\n");
        sb.append(job.aiNotes != null && !job.aiNotes.isBlank() ? job.aiNotes.trim() : "Интересная работа, без явного указания.").append("\n");
        if (job.notSuitable != null && !job.notSuitable.isEmpty()) {
            sb.append("НЕ подходит: ").append(String.join(", ", job.notSuitable)).append("\n");
        }
        if (job.salaryMin > 0) sb.append("Мин. зарплата: ").append(job.salaryMin).append("₽\n");
        sb.append("\n");
        sb.append("Для каждой карточки поставь verdict=\"yes\", если по названию/работодателю/зарплате/краткому описанию " +
            "она МОЖЕТ подойти и стоит открыть полностью для детальной оценки. verdict=\"no\" — только если явно не подходит " +
            "(видно из названия, работодателя или краткого описания). Сомневаешься — ставь \"yes\": лучше открыть лишнюю карточку, " +
            "чем пропустить подходящую по скудным данным. score всегда 50, reason — до 8 слов.\n");
        sb.append("Верни JSON-массив: [{\"id\":\"...\",\"score\":50,\"verdict\":\"yes\"|\"no\",\"reason\":\"...\"}]. Никакого текста вне массива.\n\n");
        sb.append("КАРТОЧКИ:\n");
        for (ScraperClient.SearchHit h : hits) {
            sb.append("---\n");
            sb.append("ID: ").append(h.hhId()).append("\n");
            sb.append("Название: ").append(h.title()).append("\n");
            sb.append("Работодатель: ").append(h.employerName() != null ? h.employerName() : "").append("\n");
            sb.append("Зарплата: ").append(h.salaryRawText() != null ? h.salaryRawText() : "не указана").append("\n");
            sb.append("Адрес: ").append(h.address() != null ? h.address() : "").append("\n");
            if (h.snippet() != null && !h.snippet().isBlank()) {
                // The serp card's own duties/requirements teaser — the strongest signal
                // available pre-scrape; capped so one verbose card can't bloat the batch.
                String snippet = h.snippet().length() > 300 ? h.snippet().substring(0, 300) + "…" : h.snippet();
                sb.append("Кратко о вакансии: ").append(snippet).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Wait to respect rate limits. The pause is per-provider when configured
     * (see AiProviderConfig.requestDelayMs) — the global default is sized for
     * free-tier models and needlessly throttles paid fallbacks several-fold.
     */
    private synchronized void waitForRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        Integer providerDelay = providerManager.getCurrentRequestDelayMs();
        long minInterval = providerDelay != null ? providerDelay : runtimeConfig.getAiRequestDelayMs();
        if (elapsed < minInterval) {
            Thread.sleep(minInterval - elapsed);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * Analyze with exponential backoff retry. 429 retries the SAME provider first
     * (OpenRouter's free-pool 429 is usually seconds-long congestion, not a dead
     * provider — the "models" array already absorbs per-model limits) and only
     * advances the chain once maxRetries is exhausted. 401/403 advances immediately —
     * dead credentials don't heal by retrying (incident 2026-07-17: instant-switch-on-
     * 429 parked the chain on an expired fallback key for ~10h before this fix).
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
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean isRateLimit = msg.contains("429");
                boolean isAuthError = msg.contains("401") || msg.contains("403");

                if (isAuthError || (isRateLimit && attempt >= maxRetries)) {
                    String currentProvider = providerManager.getCurrentProviderName();
                    providerManager.switchToFallback();
                    if (providerManager.isInCooldown()) {
                        log.warn("Провайдеры исчерпаны ({}). Cooldown. Последний был: {}", msg, currentProvider);
                        throw new RuntimeException("All providers exhausted, entering cooldown");
                    }
                    log.warn("{} от {}. Переключаемся на провайдера: {}",
                        isAuthError ? "Ошибка авторизации" : "Стабильный 429",
                        currentProvider, providerManager.getCurrentProviderName());
                    attempt = 0; // свежий бюджет попыток для нового провайдера
                    continue;
                }

                if (!isRateLimit && attempt >= maxRetries) {
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
        // Same floor as the prescreen path: openrouter/free routes to reasoning models
        // that burn thousands of tokens thinking before emitting the array, so a
        // per-vacancy-only budget (400 + 150*N) got the JSON truncated mid-array on
        // small batches. The cap is headroom, not extra spend (max_tokens only limits).
        String response = callLlm(prompt, Math.min(6000, 3000 + 150 * vacancies.size()));
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
        sb.append("Мин. зарплата: ").append(job.salaryMin).append("₽\n");
        if (job.experienceSummary != null && !job.experienceSummary.isBlank()) {
            sb.append("Опыт и бэкграунд кандидата: ").append(job.experienceSummary.trim()).append("\n");
        }
        sb.append("\n");

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
            sb.append("Зарплата: ").append(SalaryFormatter.forPrompt(v)).append("\n");
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
     * Hashes the scoring-relevant inputs of a search job (everything buildPrompt's
     * "ПРОФИЛЬ"/notes/candidate-background sections draw from). Two different
     * users' searches that hash the same for a given vacancy are genuinely
     * scoring-equivalent — see VacancyPipelineService's dedup-before-AI-call step,
     * which mirrors the existing scrape-reuse pattern one layer up.
     */
    public String computeCriteriaHash(SearchJob job) {
        String normalized = String.join("|",
            nullToEmpty(job.city).trim().toLowerCase(),
            sortedJoined(job.priorityDistricts),
            sortedJoined(job.skills),
            sortedJoined(job.notSuitable),
            String.valueOf(job.salaryMin),
            nullToEmpty(job.aiNotes).trim().toLowerCase(),
            nullToEmpty(job.experienceSummary).trim().toLowerCase());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is always available on any JVM; this is unreachable in practice.
            return normalized;
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String sortedJoined(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.stream().map(s -> s.trim().toLowerCase()).sorted()
            .reduce((a, b) -> a + "," + b).orElse("");
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

    // Package-private: FreeModelUpdater reuses the same call path (rate limiting,
    // provider chain, model-list fallback, token metrics) for its ranking request.
    String callLlm(String prompt, int maxTokens) throws Exception {
        return callLlm(prompt, maxTokens, null);
    }

    /**
     * modelOverride replaces the provider's configured model string for this one call —
     * FreeModelUpdater needs it because its whole reason to call is that the CONFIGURED
     * list contains a dead model: routing the ranking request through that same list
     * got a 400 back (observed live) and the AI selection silently never ran.
     */
    String callLlm(String prompt, int maxTokens, String modelOverride) throws Exception {
        String url = providerManager.getCurrentUrl();
        String key = providerManager.getCurrentKey();
        String model = modelOverride != null ? modelOverride : providerManager.getCurrentModel();
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

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", maxTokens);
        if (model.contains(",")) {
            // Comma-separated model list = OpenRouter's server-side fallback ("models"
            // array, max 3) — chosen over the "openrouter/free" router after live logs
            // showed it landing on a content-safety guard model ("User Safety: safe")
            // and reasoning models burning the whole completion budget thinking.
            List<String> models = java.util.Arrays.stream(model.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(3).toList();
            requestBody.put("model", models.get(0));
            requestBody.put("models", models);
            requestBody.put("reasoning", Map.of("enabled", false));
        }
        byte[] payload = mapper.writeValueAsBytes(requestBody);

        long startNanos = System.nanoTime();
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        metrics.recordLatency(provider, (System.nanoTime() - startNanos) / 1_000_000);

        metrics.recordRequest(provider);
        if (code == 429) {
            metrics.recordRateLimit(provider);
        } else if (code >= 400) {
            metrics.recordError(provider, code);
        }

        String body = HttpUtil.readBody(conn, code);
        if (code >= 400) {
            log.error("Ошибка LLM API {} ({}): {}", code, provider, body);
            throw new RuntimeException("LLM API returned " + code);
        }
        recordTokenUsage(provider, body);
        return body;
    }

    /** Best-effort token accounting from the response's OpenAI-compatible "usage" object — a malformed or missing field must never break analysis. */
    @SuppressWarnings("unchecked")
    private void recordTokenUsage(String provider, String body) {
        try {
            Map<?, ?> resp = mapper.readValue(body, Map.class);
            Object usage = resp.get("usage");
            if (usage instanceof Map<?, ?> u) {
                if (u.get("prompt_tokens") instanceof Number n) metrics.recordTokens(provider, "prompt", n.longValue());
                if (u.get("completion_tokens") instanceof Number n) metrics.recordTokens(provider, "completion", n.longValue());
            }
        } catch (Exception ignored) {
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
        if (content == null || content.isBlank()) {
            // Reasoning models sometimes return content=null having spent the whole
            // budget on the reasoning field; without this check that surfaced as a
            // bare NPE ("String.indexOf ... content is null") in the retry log.
            throw new RuntimeException("Ответ AI без текста (content пуст)");
        }

        String jsonArray = extractJsonArray(content);
        if (jsonArray == null) {
            throw new RuntimeException("JSON-массив не найден в ответе AI: "
                + content.substring(0, Math.min(200, content.length())));
        }
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

    /**
     * Finds the outermost balanced JSON array in the model's response text, tracking
     * bracket depth and string-literal state (so a `[`/`]` inside a quoted "reason"
     * value — e.g. a model writing about "навыки [Excel, 1C]" — doesn't get mistaken
     * for the array's real boundaries). Plain content.indexOf('[')/lastIndexOf(']')
     * would misparse that case, or accidentally include trailing prose after the
     * array as if it were part of it.
     */
    static String extractJsonArray(String content) {
        int startIdx = content.indexOf('[');
        if (startIdx < 0) return null;

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = startIdx; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return content.substring(startIdx, i + 1);
            }
        }
        return null; // never closed — truncated response
    }

    public record AiResult(String hhId, int score, String verdict, String reason) {}
}
