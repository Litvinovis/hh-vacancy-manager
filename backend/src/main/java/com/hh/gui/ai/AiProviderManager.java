package com.hh.gui.ai;

import com.hh.gui.config.AiProviderConfig;
import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Manages a chain of AI providers with automatic fallback.
 *
 * Providers are configured in RuntimeConfig as an ordered list.
 * On 429 (rate limit) the manager advances to the next provider.
 * If all providers are exhausted, enters cooldown.
 */
@Component
public class AiProviderManager {

    private static final Logger log = LoggerFactory.getLogger(AiProviderManager.class);

    public enum ProviderState { PRIMARY, FALLBACK, COOLDOWN }

    private final RuntimeConfig runtimeConfig;
    private final AiMetrics metrics;
    // state/currentIndex/cooldownUntil are read-modify-write across several steps in
    // methods like switchToFallback (check hasFallback, increment, set state) — a lone
    // volatile per field doesn't make that sequence atomic. Every method touching any
    // of these three fields is `synchronized` on `this` instead, so a scheduled run and
    // a manually-triggered run hitting a 429 at the same moment can't interleave into a
    // corrupted index/state (this manager is a shared singleton bean across all callers).
    private ProviderState state = ProviderState.PRIMARY;
    private int currentIndex = 0;
    private long cooldownUntil = 0;

    public AiProviderManager(RuntimeConfig runtimeConfig, AiMetrics metrics) {
        this.runtimeConfig = runtimeConfig;
        this.metrics = metrics;
        metrics.gauge("ai_cooldown_active", "1 if all AI providers are exhausted and in cooldown, 0 otherwise",
            this, m -> m.isInCooldown() ? 1 : 0);
    }

    /** Get the current active provider config. */
    private synchronized AiProviderConfig getCurrentProvider() {
        List<AiProviderConfig> providers = getActiveProviders();
        if (providers.isEmpty()) return null;
        if (currentIndex >= providers.size()) currentIndex = 0;
        return providers.get(currentIndex);
    }

    /** Get the next provider (for fallback), or null if current is last. */
    private AiProviderConfig getNextProvider() {
        List<AiProviderConfig> providers = getActiveProviders();
        if (providers.isEmpty()) return null;
        int next = currentIndex + 1;
        if (next >= providers.size()) return null;
        return providers.get(next);
    }

    private List<AiProviderConfig> getActiveProviders() {
        List<AiProviderConfig> all = runtimeConfig.getAiProviders();
        // Filter out providers without a configured key
        return all.stream()
            .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
            .toList();
    }

    /** Returns the total number of configured (active) providers. */
    public int getProviderCount() {
        return getActiveProviders().size();
    }

    public boolean hasPrimary() {
        return getProviderCount() > 0;
    }

    /** Returns true if there is a next provider to fall back to. */
    public synchronized boolean hasFallback() {
        List<AiProviderConfig> providers = getActiveProviders();
        return currentIndex + 1 < providers.size();
    }

    public synchronized String getCurrentUrl() {
        resolveState();
        AiProviderConfig p = getCurrentProvider();
        return p != null ? p.getUrl() : "";
    }

    public synchronized String getCurrentKey() {
        resolveState();
        AiProviderConfig p = getCurrentProvider();
        return p != null ? p.getApiKey() : "";
    }

    public synchronized String getCurrentModel() {
        resolveState();
        AiProviderConfig p = getCurrentProvider();
        return p != null ? p.getModel() : "";
    }

    /**
     * Per-provider minimum delay between requests, or null when the provider
     * doesn't override the global aiRequestDelayMs (the free-tier-sized default).
     */
    public synchronized Integer getCurrentRequestDelayMs() {
        resolveState();
        AiProviderConfig p = getCurrentProvider();
        return p != null ? p.getRequestDelayMs() : null;
    }

    /** Get the name of the currently active provider. */
    public synchronized String getCurrentProviderName() {
        resolveState();
        if (state == ProviderState.COOLDOWN) {
            AiProviderConfig p = getCurrentProvider();
            String name = p != null ? p.getName() : "unknown";
            return name + " (cooldown)";
        }
        AiProviderConfig p = getCurrentProvider();
        return p != null ? p.getName() : "unknown";
    }

    /**
     * Switch to the next provider in the chain.
     * If at the end of the list, enter cooldown.
     */
    public synchronized void switchToFallback() {
        if (hasFallback()) {
            String currentName = getCurrentProviderName();
            currentIndex++;
            String nextName = getCurrentProviderName();
            log.warn("Провайдер {} rate limited (429). Переключаемся на {}.", currentName, nextName);
            metrics.recordRateLimit(currentName);
            metrics.recordProviderSwitch(currentName, nextName);
            state = ProviderState.FALLBACK;
        } else {
            String currentName = getCurrentProviderName();
            log.warn("Последний провайдер {} (429), fallback недоступен. Входим в cooldown.", currentName);
            metrics.recordRateLimit(currentName);
            metrics.recordProviderSwitch(currentName, "cooldown");
            enterCooldown();
        }
    }

    /**
     * If all providers failed, enter cooldown.
     */
    public synchronized void enterCooldown() {
        state = ProviderState.COOLDOWN;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hours = runtimeConfig.getCooldownHours();
        if (hours > 0) {
            cal.add(java.util.Calendar.HOUR_OF_DAY, hours);
        } else {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 6);
        }
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cooldownUntil = cal.getTimeInMillis();
        log.warn("AI провайдеры исчерпаны. Cooldown до {} (hours={})",
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(cooldownUntil)),
            hours);
    }

    /** Check if currently in cooldown. */
    public synchronized boolean isInCooldown() {
        resolveState();
        return state == ProviderState.COOLDOWN;
    }

    /** Get cooldown until timestamp (0 = not in cooldown). */
    public synchronized long getCooldownUntil() {
        return cooldownUntil;
    }

    /** Reset to the first provider in the list. */
    public synchronized void reset() {
        currentIndex = 0;
        state = ProviderState.PRIMARY;
        cooldownUntil = 0;
        log.info("AI провайдер сброшен на первый в списке");
    }

    /** Manual force to a specific provider by index. */
    public synchronized void forceProvider(int index) {
        List<AiProviderConfig> providers = getActiveProviders();
        if (index >= 0 && index < providers.size()) {
            currentIndex = index;
            state = ProviderState.PRIMARY;
            log.info("AI провайдер принудительно переключён на: {}", providers.get(index).getName());
        }
    }

    /** Force fallback to next provider. */
    public synchronized void forceFallback() {
        if (hasFallback()) {
            currentIndex++;
            state = ProviderState.FALLBACK;
            log.info("AI провайдер принудительно переключён на fallback: {}", getCurrentProviderName());
        }
    }

    public synchronized ProviderState getState() {
        resolveState();
        return state;
    }

    public synchronized int getCurrentIndex() {
        return currentIndex;
    }

    public synchronized String getStateLabel() {
        if (isInCooldown()) {
            return "cooldown-until-" + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(cooldownUntil));
        }
        return switch (state) {
            case PRIMARY -> getProviderLabel(currentIndex);
            case FALLBACK -> getProviderLabel(currentIndex);
            case COOLDOWN -> "cooldown";
        };
    }

    private String getProviderLabel(int index) {
        List<AiProviderConfig> providers = getActiveProviders();
        if (index >= providers.size()) return "unknown";
        AiProviderConfig p = providers.get(index);
        String shortName = p.getName();
        String model = p.getModel();
        String suffix = "";
        if (model != null && model.contains(",")) {
            // Comma-separated fallback list (see VacancyAiAnalyzer.callLlm) — label by
            // the primary model plus how many fallbacks stand behind it.
            String[] list = model.split(",");
            model = list[0].trim();
            suffix = "+" + (list.length - 1);
        }
        if (model != null && !model.isBlank()) {
            String[] parts = model.split("/");
            String shortModel = parts[parts.length - 1]
                .replace("-3-mini", "")
                .replace("-2-vision", "");
            return shortName + "/" + shortModel + suffix;
        }
        return shortName;
    }

    private synchronized void resolveState() {
        // If configured to use only one specific provider
        List<AiProviderConfig> providers = getActiveProviders();
        if (providers.isEmpty()) {
            state = ProviderState.COOLDOWN;
            return;
        }
        // Check if cooldown expired
        if (state == ProviderState.COOLDOWN && System.currentTimeMillis() >= cooldownUntil) {
            currentIndex = 0;
            state = ProviderState.PRIMARY;
            log.info("Cooldown истёк. Переключаемся на первый провайдер: {}", providers.get(0).getName());
        }
        // Fix out-of-bounds index
        if (currentIndex >= providers.size()) {
            currentIndex = 0;
        }
    }
}
