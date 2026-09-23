package com.hh.gui.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Что известно о каждой модели сверх одной пробы раз в 12 часов (24.09.2026).
 *
 * Раньше выбор модели опирался только на пробу из двух вакансий, и это давало два вида
 * ошибок. Исправную модель выбрасывали за временную перегрузку (23.09 nemotron-3-ultra
 * исключили после двух таймаутов подряд, хотя она живая). А модель, прошедшую пробу,
 * но ломающую JSON или пропускающую вакансии в реальной работе, никто не замечал.
 *
 * Здесь копится:
 * <ul>
 *   <li>временные сбои проверки подряд (таймаут, 5xx) — модель исключается только после
 *       {@link #TRANSIENT_STRIKES_TO_EVICT} таких проверок подряд, а не после первой;</li>
 *   <li>блокировка кандидата на {@link #BLOCK_DAYS} дня после 403 от провайдера — такие
 *       («Blocked by Google AI Studio») раньше перепроверялись каждые 12 часов;</li>
 *   <li>исходы последних {@link #OUTCOME_WINDOW} реальных вызовов анализа — по ним модель
 *       убирается из цепочки, если в работе ломает ответ;</li>
 *   <li>скорость ответа — по ней выбирается одна из моделей с равной оценкой пробы.</li>
 * </ul>
 * Хранится в data/model-health.json: деплой бывает несколько раз в день, и счётчики в
 * памяти не доживали бы до решения.
 */
@Component
public class ModelHealth {

    private static final Logger log = LoggerFactory.getLogger(ModelHealth.class);

    static final int TRANSIENT_STRIKES_TO_EVICT = 3;
    static final int BLOCK_DAYS = 3;
    static final int OUTCOME_WINDOW = 20;
    static final int MIN_OUTCOMES_TO_JUDGE = 10;
    static final double MAX_FAILURE_RATE = 0.5;
    private static final String FILE = "model-health.json";

    /** Одна модель. Поля публичные — так их без аннотаций пишет и читает Jackson. */
    public static class Entry {
        public int transientStrikes;
        public long blockedUntil;
        /** Последние исходы реальных вызовов, самые свежие в конце: '1' — успех, '0' — сбой. */
        public String outcomes = "";
        /** Сглаженное время ответа, мс (0 — не измерялось). */
        public double latencyMs;
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    private final Path file;

    /** Без файла — для тестов и там, где хранить негде. */
    public ModelHealth() {
        this.file = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelHealth(@Value("${app.data-dir:${user.dir}/data}") String dataDir) {
        this.file = dataDir == null ? null : Paths.get(dataDir, FILE);
        load();
    }

    // ── проба ──

    /** Временный сбой пробы; возвращает, сколько их подряд. */
    public synchronized int recordTransientFailure(String model) {
        int strikes = ++entry(model).transientStrikes;
        save();
        return strikes;
    }

    /** Проба ответила (хорошо или плохо) — серия временных сбоев прерывается. */
    public synchronized void clearTransientFailures(String model) {
        Entry e = entries.get(model);
        if (e != null && e.transientStrikes != 0) {
            e.transientStrikes = 0;
            save();
        }
    }

    public synchronized void block(String model, long nowMs) {
        entry(model).blockedUntil = nowMs + BLOCK_DAYS * 24L * 3600 * 1000;
        save();
    }

    public synchronized boolean isBlocked(String model, long nowMs) {
        Entry e = entries.get(model);
        return e != null && e.blockedUntil > nowMs;
    }

    // ── реальная работа ──

    public synchronized void recordOutcome(String model, boolean ok) {
        if (model == null || model.isBlank()) return;
        Entry e = entry(model);
        String next = e.outcomes + (ok ? '1' : '0');
        e.outcomes = next.length() > OUTCOME_WINDOW ? next.substring(next.length() - OUTCOME_WINDOW) : next;
        save();
    }

    /** Доля сбоев в последних вызовах; null — вызовов слишком мало, чтобы судить. */
    public synchronized Double failureRate(String model) {
        Entry e = entries.get(model);
        if (e == null || e.outcomes.length() < MIN_OUTCOMES_TO_JUDGE) return null;
        long failures = e.outcomes.chars().filter(c -> c == '0').count();
        return failures / (double) e.outcomes.length();
    }

    /** Модель в реальной работе ломает больше половины ответов — держать её в цепочке нельзя. */
    public synchronized boolean failsInProduction(String model) {
        Double rate = failureRate(model);
        return rate != null && rate >= MAX_FAILURE_RATE;
    }

    /** Модель заменили — её прошлые сбои не должны помешать ей вернуться, если исправится. */
    public synchronized void resetOutcomes(String model) {
        Entry e = entries.get(model);
        if (e != null) {
            e.outcomes = "";
            save();
        }
    }

    public synchronized void recordLatency(String model, long ms) {
        if (model == null || model.isBlank() || ms <= 0) return;
        Entry e = entry(model);
        e.latencyMs = e.latencyMs <= 0 ? ms : e.latencyMs * 0.7 + ms * 0.3;
        save();
    }

    /** Сглаженное время ответа; {@link Double#MAX_VALUE}, если не измерялось (такие — в конец). */
    public synchronized double latencyMs(String model) {
        Entry e = entries.get(model);
        return e == null || e.latencyMs <= 0 ? Double.MAX_VALUE : e.latencyMs;
    }

    public synchronized Map<String, Entry> snapshot() {
        return new LinkedHashMap<>(entries);
    }

    private Entry entry(String model) {
        return entries.computeIfAbsent(model, k -> new Entry());
    }

    // ── хранение ──

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Map<String, Entry> loaded = mapper.readValue(file.toFile(),
                new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Entry>>() {});
            entries.putAll(loaded);
        } catch (Exception e) {
            log.warn("Не удалось прочитать {} ({}) — здоровье моделей начинается с нуля", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        try {
            File dir = file.getParent().toFile();
            if (!dir.exists()) dir.mkdirs();
            Path tmp = file.resolveSibling(FILE + ".tmp");
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Не удалось сохранить {}: {}", file, e.getMessage());
        }
    }
}
