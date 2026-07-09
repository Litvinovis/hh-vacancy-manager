package com.hh.gui.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

/**
 * Runtime-изменяемые настройки приложения.
 * Инициализируются из defaults / JSON-файла, затем могут меняться через API без ребута.
 * Все изменения сохраняются в JSON-файл и восстанавливаются при перезапуске.
 */
@Component
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String CONFIG_FILE = "runtime-config.json";

    @Value("${app.data-dir:${user.dir}/data}")
    private String dataDir = System.getProperty("user.dir") + "/data";

    // ═══════ Старые @Value пропсы для seed-инициализации провайдеров ═══════
    @Value("${app.ai.api-url:https://openrouter.ai/api/v1/chat/completions}")
    private String seedPrimaryUrl;

    @Value("${app.ai.api-key:}")
    private String seedPrimaryKey;

    @Value("${app.ai.model:openrouter/auto}")
    private String seedPrimaryModel;

    @Value("${app.ai.fallback-url:https://api.x.ai/v1/chat/completions}")
    private String seedFallbackUrl;

    @Value("${app.ai.fallback-key:}")
    private String seedFallbackKey;

    @Value("${app.ai.fallback-model:grok-3-mini}")
    private String seedFallbackModel;

    // ═══════ Список AI провайдеров ═══════
    private volatile List<AiProviderConfig> aiProviders = new ArrayList<>();

    public List<AiProviderConfig> getAiProviders() {
        return List.copyOf(aiProviders);
    }

    public synchronized void setAiProviders(List<AiProviderConfig> providers) {
        this.aiProviders = new ArrayList<>(providers);
        saveToFile();
        log.info("Список AI провайдеров обновлён: {} провайдеров", providers.size());
    }

    @SuppressWarnings("unchecked")
    private synchronized void deserializeProviders(String json) {
        if (json == null || json.isBlank()) {
            aiProviders = new ArrayList<>();
            return;
        }
        try {
            aiProviders = ((List<Map<String, Object>>) MAPPER.readValue(json, List.class)).stream()
                .map(m -> {
                    Map<String, Object> map = (Map<String, Object>) m;
                    AiProviderConfig p = new AiProviderConfig();
                    p.setName((String) map.getOrDefault("name", ""));
                    p.setUrl((String) map.getOrDefault("url", ""));
                    p.setApiKey((String) map.getOrDefault("apiKey", ""));
                    p.setModel((String) map.getOrDefault("model", ""));
                    if (map.get("requestDelayMs") instanceof Number n) {
                        p.setRequestDelayMs(n.intValue());
                    }
                    return p;
                })
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("Ошибка десериализации провайдеров: {}", e.getMessage());
            aiProviders = new ArrayList<>();
        }
    }

    /** Инициализировать провайдеров из старых @Value пропсов. */
    private synchronized void seedFromLegacyProps() {
        List<AiProviderConfig> seed = new ArrayList<>();
        if (seedPrimaryKey != null && !seedPrimaryKey.isBlank()) {
            AiProviderConfig primary = new AiProviderConfig();
            String name = detectNameFromUrl(seedPrimaryUrl);
            primary.setName(name);
            primary.setUrl(seedPrimaryUrl);
            primary.setApiKey(seedPrimaryKey);
            primary.setModel(seedPrimaryModel);
            seed.add(primary);
            log.info("Seed AI провайдер: {} (из app.ai.*)", name);
        }
        if (seedFallbackKey != null && !seedFallbackKey.isBlank()) {
            AiProviderConfig fallback = new AiProviderConfig();
            String name = detectNameFromUrl(seedFallbackUrl);
            fallback.setName(name);
            fallback.setUrl(seedFallbackUrl);
            fallback.setApiKey(seedFallbackKey);
            fallback.setModel(seedFallbackModel);
            seed.add(fallback);
            log.info("Seed AI провайдер: {} (из app.ai.fallback-*)", name);
        }
        if (!seed.isEmpty()) {
            aiProviders = seed;
            saveToFile();
            log.info("Инициализировано {} AI провайдеров из legacy @Value пропсов", seed.size());
        }
    }

    private static String detectNameFromUrl(String url) {
        if (url == null) return "unknown";
        String u = url.toLowerCase();
        if (u.contains("github")) return "github-models";
        if (u.contains("azure.com")) return "github-models";
        if (u.contains("x.ai")) return "grok";
        if (u.contains("openrouter")) return "openrouter";
        if (u.contains("groq")) return "groq";
        return "custom";
    }

    // ═══════ Поля с defaults ═══════

    private volatile int maxPerRun = 30;
    private volatile int pipelineIntervalMs = 600000; // 10 мин
    private volatile String dailyCron = "0 0 12 * * *"; // ежедневно 12:00
    private volatile int maxRetries = 3;
    private volatile int requestDelayMs = 1500; // HH RSS задержка
    private volatile int aiRequestDelayMs = 12000; // Задержка между AI-запросами (free-tier rate limit)
    private volatile int httpConnectTimeoutMs = 30000;
    private volatile int httpReadTimeoutMs = 120000;
    private volatile int minScore = 50;
    private volatile int maxApproved = 10;
    private volatile int salaryMinRemote = 40000;
    private volatile int cooldownHours = 0;
    private volatile int pipelineBatchSize = 10;
    private volatile boolean notificationsEnabled = false;
    private volatile int aiBatchSize = 5;
    private volatile boolean pipelineEnabled = true;
    private volatile int urlSearchEarlyStopThreshold = 3;
    private volatile int cardPrescreenBatchSize = 30;
    private volatile int urlSearchAdSlotsPerPage = 20;

    // ═══════ Persistence ═══════

    @PostConstruct
    void loadFromFile() {
        if (dataDir == null) return;
        File file = getConfigFile();
        if (!file.exists()) {
            log.info("Runtime config file не найден ({}), используем defaults", file.getAbsolutePath());
            seedFromLegacyProps(); // всё равно пытаемся инициализировать провайдеров
            return;
        }
        try {
            Map<String, Object> saved = MAPPER.readValue(file, new TypeReference<>() {});
            // Десериализуем список провайдеров отдельно
            Object providersRaw = saved.remove("aiProviders");
            if (providersRaw instanceof String providersJson) {
                deserializeProviders(providersJson);
            } else if (providersRaw instanceof List) {
                try {
                    String json = MAPPER.writeValueAsString(providersRaw);
                    deserializeProviders(json);
                } catch (Exception e) {
                    log.warn("Не удалось разобрать aiProviders: {}", e.getMessage());
                }
            }
            apply(saved, true); // silent = true — не пишем лог и не триггерим save
            log.info("Runtime config загружен из {} ({} параметров, {} провайдеров)", file.getAbsolutePath(), saved.size(), aiProviders.size());

            // Если провайдеров нет — инициализируем из старых @Value пропсов
            if (aiProviders.isEmpty()) {
                seedFromLegacyProps();
            }
        } catch (Exception e) {
            log.warn("Не удалось загрузить runtime config из {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    private synchronized void saveToFile() {
        File file = getConfigFile();
        if (file == null) return;
        try {
            // Создаём родительскую директорию если нужно
            file.getParentFile().mkdirs();
            MAPPER.writeValue(file, toMap());
            log.debug("Runtime config сохранён в {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Не удалось сохранить runtime config в {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    private File getConfigFile() {
        if (dataDir == null) return null;
        return Paths.get(dataDir, CONFIG_FILE).toFile();
    }

    // ═══════ Метода-дескрипторы для UI ═══════

    public List<SettingDescriptor> getDescriptors() {
        return List.of(
            SettingDescriptor.of("maxPerRun", "Лимит вакансий за запуск",
                "Максимальное количество вакансий, отправляемых на AI-анализ за один запуск пайплайна. " +
                "Слишком большое значение расходует квоту API и создаёт нагрузку на БД.",
                "number", 1, 500, maxPerRun),

            SettingDescriptor.of("pipelineIntervalMs", "Интервал пайплайна",
                "Интервал (в миллисекундах) между автоматическими запусками пайплайна. " +
                "600000 = 10 минут, 3600000 = 1 час. " +
                "Изменение вступает в силу при следующем запуске.",
                "number", 60000, 86400000, pipelineIntervalMs),

            SettingDescriptor.of("dailyCron", "Расписание дневного анализа",
                "Расписание полного AI-анализа всех необработанных вакансий. " +
                "Формат: cron-выражение (секунды минуты часы день месяц день_недели). " +
                "Пример: '0 0 12 * * *' = каждый день в 12:00.",
                "cron", null, null, dailyCron),

            SettingDescriptor.of("maxRetries", "Повторных попыток AI",
                "Количество повторных попыток при ошибке AI-запроса (таймаут, 5xx). " +
                "Каждая попытка ждёт дольше предыдущей (экспоненциальная задержка).",
                "number", 1, 10, maxRetries),

            SettingDescriptor.of("requestDelayMs", "Задержка RSS-запросов",
                "Задержка в миллисекундах между запросами к HH.ru RSS. " +
                "Слишком частые запросы могут привести к блокировке IP. " +
                "1500 = 1.5 секунды.",
                "number", 500, 10000, requestDelayMs),

            SettingDescriptor.of("aiRequestDelayMs", "Задержка AI-запросов",
                "Задержка в миллисекундах между запросами к AI (LLM). " +
                "Бесплатные модели (free-tier) требуют паузу, иначе 429 rate limit. " +
                "12000 = 12 секунд.",
                "number", 5000, 60000, aiRequestDelayMs),

            SettingDescriptor.of("httpConnectTimeoutMs", "Таймаут соединения",
                "Таймаут подключения (в миллисекундах) для HTTP-запросов. " +
                "Применяется к AI API и HH RSS. " +
                "30000 = 30 секунд.",
                "number", 5000, 120000, httpConnectTimeoutMs),

            SettingDescriptor.of("httpReadTimeoutMs", "Таймаут чтения",
                "Таймаут чтения ответа (в миллисекундах) для HTTP-запросов. " +
                "Для AI API нужен большой таймаут, т.к. LLM может отвечать до 2 минут. " +
                "120000 = 2 минуты.",
                "number", 10000, 300000, httpReadTimeoutMs),

            SettingDescriptor.of("minScore", "Мин. скор уведомлений",
                "Минимальный AI-скор (0-100) для отправки уведомления в Telegram. " +
                "Вакансии с меньшим скором не попадают в отчёт.",
                "number", 0, 100, minScore),

            SettingDescriptor.of("maxApproved", "Лимит уведомлений",
                "Максимальное количество одобренных вакансий в одном Telegram-уведомлении. " +
                "Ограничивает размер одного сообщения.",
                "number", 1, 50, maxApproved),

            SettingDescriptor.of("salaryMinRemote", "Мин. зарплата удалёнки",
                "Минимальная зарплата (в рублях) для удалённых вакансий. " +
                "Вакансии с зарплатой ниже этого значения не попадают в результаты поиска.",
                "number", 0, 500000, salaryMinRemote),

            SettingDescriptor.of("cooldownHours", "Охлаждение после 429",
                "Продолжительность блокировки AI-запросов после ошибки 429 (rate limit). " +
                "0 = до 06:00 следующего дня (по умолчанию). " +
                "Иначе — указанное количество часов от текущего момента.",
                "number", 0, 72, cooldownHours),

            SettingDescriptor.of("pipelineBatchSize", "Размер пачки пайплайна",
                "Количество вакансий в одном пакете пайплайна. " +
                "Влияет на размер выборки из БД и количество вакансий за один AI-запрос.",
                "number", 1, 100, pipelineBatchSize),

            SettingDescriptor.of("notificationsEnabled", "Уведомления Telegram",
                "Включить/выключить отправку уведомлений о новых вакансиях в Telegram.",
                "boolean", null, null, notificationsEnabled),

            SettingDescriptor.of("aiBatchSize", "Размер пачки AI",
                "Количество вакансий, отправляемых в одном AI-запросе. " +
                "Большие пачки экономят токены промпта, но увеличивают риск таймаута.",
                "number", 1, 50, aiBatchSize),

            SettingDescriptor.of("pipelineEnabled", "Автозапуск пайплайна",
                "Включить/выключить автоматический запуск пайплайна по расписанию. " +
                "При выключении можно запускать только вручную через кнопку.",
                "boolean", null, null, pipelineEnabled),

            SettingDescriptor.of("urlSearchEarlyStopThreshold", "Порог остановки по ссылке",
                "При обходе страниц поиска по ссылке — сколько подряд уже известных вакансий " +
                "должно встретиться, чтобы считать, что дальше только старые результаты, и прекратить обход.",
                "number", 1, 20, urlSearchEarlyStopThreshold),

            SettingDescriptor.of("cardPrescreenBatchSize", "Размер пачки прескрининга карточек",
                "Сколько карточек из выдачи (без полного скрейпинга) отправляется за один AI-запрос " +
                "на предварительную фильтрацию \"похоже/не похоже на интересную вакансию\".",
                "number", 1, 100, cardPrescreenBatchSize),

            SettingDescriptor.of("urlSearchAdSlotsPerPage", "Рекламных карточек сверху страницы",
                "Сколько первых карточек на каждой странице выдачи hh.ru считаются рекламными/премиум " +
                "местами (закреплены сверху независимо от даты публикации) и поэтому не учитываются " +
                "при подсчёте подряд идущих уже известных вакансий для остановки поиска по ссылке.",
                "number", 0, 50, urlSearchAdSlotsPerPage)
        );
    }

    // ═══════ Валидация и обновление ═══════

    /**
     * Обновляет параметры из Map. Невалидные ключи игнорируются.
     * Невалидные значения отвергаются с возвратом ошибок.
     * При успешном обновлении — сохраняет в JSON-файл.
     *
     * @return Map с ошибками (пустой = всё ок)
     */
    public Map<String, String> apply(Map<String, Object> updates) {
        return apply(updates, false);
    }

    private Map<String, String> apply(Map<String, Object> updates, boolean silent) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            try {
                switch (key) {
                    case "maxPerRun" -> setMaxPerRun(toInt(value, errors, key, 1, 500));
                    case "pipelineIntervalMs" -> setPipelineIntervalMs(toInt(value, errors, key, 60000, 86400000));
                    case "dailyCron" -> setDailyCron(toCron(value, errors, key));
                    case "maxRetries" -> setMaxRetries(toInt(value, errors, key, 1, 10));
                    case "requestDelayMs" -> setRequestDelayMs(toInt(value, errors, key, 500, 10000));
                    case "aiRequestDelayMs" -> setAiRequestDelayMs(toInt(value, errors, key, 5000, 60000));
                    case "httpConnectTimeoutMs" -> setHttpConnectTimeoutMs(toInt(value, errors, key, 5000, 120000));
                    case "httpReadTimeoutMs" -> setHttpReadTimeoutMs(toInt(value, errors, key, 10000, 300000));
                    case "minScore" -> setMinScore(toInt(value, errors, key, 0, 100));
                    case "maxApproved" -> setMaxApproved(toInt(value, errors, key, 1, 50));
                    case "salaryMinRemote" -> setSalaryMinRemote(toInt(value, errors, key, 0, 500000));
                    case "cooldownHours" -> setCooldownHours(toInt(value, errors, key, 0, 72));
                    case "pipelineBatchSize" -> setPipelineBatchSize(toInt(value, errors, key, 1, 100));
                    case "notificationsEnabled" -> setNotificationsEnabled(toBool(value, errors, key));
                    case "aiBatchSize" -> setAiBatchSize(toInt(value, errors, key, 1, 50));
                    case "pipelineEnabled" -> setPipelineEnabled(toBool(value, errors, key));
                    case "urlSearchEarlyStopThreshold" -> setUrlSearchEarlyStopThreshold(toInt(value, errors, key, 1, 20));
                    case "cardPrescreenBatchSize" -> setCardPrescreenBatchSize(toInt(value, errors, key, 1, 100));
                    case "urlSearchAdSlotsPerPage" -> setUrlSearchAdSlotsPerPage(toInt(value, errors, key, 0, 50));
                    default -> errors.put(key, "Неизвестный параметр: " + key);
                }
            } catch (IllegalArgumentException e) {
                errors.put(key, e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            if (!silent) {
                log.info("Настройки обновлены: {}", updates.keySet());
            }
            // Сохраняем в файл если были реальные изменения
            if (!updates.isEmpty()) {
                saveToFile();
            }
        } else if (!silent) {
            log.warn("Ошибки обновления настроек: {}", errors);
        }
        return errors;
    }

    /**
     * Возвращает текущие значения всех параметров как Map.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiProviders", aiProviders); // сохраняем как список, не строку
        m.put("maxPerRun", maxPerRun);
        m.put("pipelineIntervalMs", pipelineIntervalMs);
        m.put("dailyCron", dailyCron);
        m.put("maxRetries", maxRetries);
        m.put("requestDelayMs", requestDelayMs);
        m.put("aiRequestDelayMs", aiRequestDelayMs);
        m.put("httpConnectTimeoutMs", httpConnectTimeoutMs);
        m.put("httpReadTimeoutMs", httpReadTimeoutMs);
        m.put("minScore", minScore);
        m.put("maxApproved", maxApproved);
        m.put("salaryMinRemote", salaryMinRemote);
        m.put("cooldownHours", cooldownHours);
        m.put("pipelineBatchSize", pipelineBatchSize);
        m.put("notificationsEnabled", notificationsEnabled);
        m.put("aiBatchSize", aiBatchSize);
        m.put("pipelineEnabled", pipelineEnabled);
        m.put("urlSearchEarlyStopThreshold", urlSearchEarlyStopThreshold);
        m.put("cardPrescreenBatchSize", cardPrescreenBatchSize);
        m.put("urlSearchAdSlotsPerPage", urlSearchAdSlotsPerPage);
        return m;
    }

    // ═══════ Валидаторы ═══════

    private int toInt(Object value, Map<String, String> errors, String key, int min, int max) {
        if (value instanceof Number n) {
            int v = n.intValue();
            if (v < min || v > max) {
                errors.put(key, String.format("Значение %d вне диапазона [%d, %d]", v, min, max));
                throw new IllegalArgumentException(errors.get(key));
            }
            return v;
        }
        if (value instanceof String s) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min || v > max) {
                    errors.put(key, String.format("Значение %d вне диапазона [%d, %d]", v, min, max));
                    throw new IllegalArgumentException(errors.get(key));
                }
                return v;
            } catch (NumberFormatException e) {
                errors.put(key, "Нецелое число: " + s);
                throw new IllegalArgumentException(errors.get(key));
            }
        }
        errors.put(key, "Неверный тип: ожидается число");
        throw new IllegalArgumentException(errors.get(key));
    }

    private boolean toBool(Object value, Map<String, String> errors, String key) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        errors.put(key, "Неверный тип: ожидается true/false");
        throw new IllegalArgumentException(errors.get(key));
    }

    private String toCron(Object value, Map<String, String> errors, String key) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            String[] parts = trimmed.split("\\s+");
            if (parts.length != 6) {
                errors.put(key, "Cron должен содержать 6 полей (сек мин час день месяц день_недели)");
                throw new IllegalArgumentException(errors.get(key));
            }
            return trimmed;
        }
        errors.put(key, "Неверный тип: ожидается строка cron");
        throw new IllegalArgumentException(errors.get(key));
    }

    // ═══════ Геттеры/сеттеры ═══════

    public int getMaxPerRun() { return maxPerRun; }
    public void setMaxPerRun(int v) { this.maxPerRun = v; }

    public int getPipelineIntervalMs() { return pipelineIntervalMs; }
    public void setPipelineIntervalMs(int v) { this.pipelineIntervalMs = v; }

    public String getDailyCron() { return dailyCron; }
    public void setDailyCron(String v) { this.dailyCron = v; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }

    public int getRequestDelayMs() { return requestDelayMs; }
    public void setRequestDelayMs(int v) { this.requestDelayMs = v; }

    public int getAiRequestDelayMs() { return aiRequestDelayMs; }
    public void setAiRequestDelayMs(int v) { this.aiRequestDelayMs = v; }

    public int getHttpConnectTimeoutMs() { return httpConnectTimeoutMs; }
    public void setHttpConnectTimeoutMs(int v) { this.httpConnectTimeoutMs = v; }

    public int getHttpReadTimeoutMs() { return httpReadTimeoutMs; }
    public void setHttpReadTimeoutMs(int v) { this.httpReadTimeoutMs = v; }

    public int getMinScore() { return minScore; }
    public void setMinScore(int v) { this.minScore = v; }

    public int getMaxApproved() { return maxApproved; }
    public void setMaxApproved(int v) { this.maxApproved = v; }

    public int getSalaryMinRemote() { return salaryMinRemote; }
    public void setSalaryMinRemote(int v) { this.salaryMinRemote = v; }

    public int getCooldownHours() { return cooldownHours; }
    public void setCooldownHours(int v) { this.cooldownHours = v; }

    public int getPipelineBatchSize() { return pipelineBatchSize; }
    public void setPipelineBatchSize(int v) { this.pipelineBatchSize = v; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean v) { this.notificationsEnabled = v; }

    public int getAiBatchSize() { return aiBatchSize; }
    public void setAiBatchSize(int v) { this.aiBatchSize = v; }

    public boolean isPipelineEnabled() { return pipelineEnabled; }
    public void setPipelineEnabled(boolean v) { this.pipelineEnabled = v; }

    public int getUrlSearchEarlyStopThreshold() { return urlSearchEarlyStopThreshold; }
    public void setUrlSearchEarlyStopThreshold(int v) { this.urlSearchEarlyStopThreshold = v; }

    public int getCardPrescreenBatchSize() { return cardPrescreenBatchSize; }
    public void setCardPrescreenBatchSize(int v) { this.cardPrescreenBatchSize = v; }

    public int getUrlSearchAdSlotsPerPage() { return urlSearchAdSlotsPerPage; }
    public void setUrlSearchAdSlotsPerPage(int v) { this.urlSearchAdSlotsPerPage = v; }

    // ═══════ Дескриптор для UI ═══════

    public static class SettingDescriptor {
        public String key;
        public String label;
        public String description;
        public String type; // "number", "boolean", "cron"
        public Integer min;
        public Integer max;
        public Object currentValue;

        public static SettingDescriptor of(String key, String label, String description,
                                            String type, Integer min, Integer max, Object currentValue) {
            SettingDescriptor d = new SettingDescriptor();
            d.key = key;
            d.label = label;
            d.description = description;
            d.type = type;
            d.min = min;
            d.max = max;
            d.currentValue = currentValue;
            return d;
        }
    }
}
