package com.hh.gui.config;

/**
 * Конфигурация одного AI-провайдера в цепочке.
 * Хранится в runtime-config.json, редактируется через UI настроек.
 */
public class AiProviderConfig {

    private String name = "";
    private String url = "";
    private String apiKey = "";
    private String model = "";
    // Минимальная пауза между запросами именно к этому провайдеру, мс.
    // null → глобальный aiRequestDelayMs. Смысл: 12-секундная пауза нужна только
    // free-tier моделям; платный fallback она замедляет в разы без всякой причины.
    private Integer requestDelayMs = null;

    public AiProviderConfig() {}

    public AiProviderConfig(String name, String url, String apiKey, String model) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getRequestDelayMs() { return requestDelayMs; }
    public void setRequestDelayMs(Integer requestDelayMs) { this.requestDelayMs = requestDelayMs; }
}
