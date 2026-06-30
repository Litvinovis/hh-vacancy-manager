package com.hh.gui.ai;

import com.hh.gui.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manages AI provider selection with automatic fallback.
 *
 * Provider chain:
 *   1. Primary (OpenRouter or configured main provider)
 *   2. Fallback (Grok/xAI) — used when primary returns 429 or fails
 *   3. Cooldown — if both fail, stop making requests for a configurable period
 */
@Component
public class AiProviderManager {

    private static final Logger log = LoggerFactory.getLogger(AiProviderManager.class);

    public enum ProviderState { PRIMARY, FALLBACK, COOLDOWN }

    @Value("${app.ai.api-url:https://openrouter.ai/api/v1/chat/completions}")
    private String primaryUrl;

    @Value("${app.ai.api-key:}")
    private String primaryKey;

    @Value("${app.ai.model:openrouter/auto}")
    private String primaryModel;

    @Value("${app.ai.fallback-url:https://api.x.ai/v1/chat/completions}")
    private String fallbackUrl;

    @Value("${app.ai.fallback-key:}")
    private String fallbackKey;

    @Value("${app.ai.fallback-model:grok-3-mini}")
    private String fallbackModel;

    @Value("${app.ai.provider:auto}")
    private String configuredProvider;

    private final RuntimeConfig runtimeConfig;
    private ProviderState state = ProviderState.PRIMARY;
    private volatile long cooldownUntil = 0;

    public AiProviderManager(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /** Returns true if fallback provider is configured. */
    public boolean hasFallback() {
        return fallbackKey != null && !fallbackKey.isBlank()
            && fallbackUrl != null && !fallbackUrl.isBlank();
    }

    /** Returns true if primary provider is configured. */
    public boolean hasPrimary() {
        return primaryKey != null && !primaryKey.isBlank();
    }

    /**
     * Get the current API endpoint URL based on provider state.
     */
    public String getCurrentUrl() {
        resolveState();
        return switch (state) {
            case PRIMARY, COOLDOWN -> primaryUrl;
            case FALLBACK -> hasFallback() ? fallbackUrl : primaryUrl;
        };
    }

    /**
     * Get the current API key based on provider state.
     */
    public String getCurrentKey() {
        resolveState();
        return switch (state) {
            case PRIMARY, COOLDOWN -> primaryKey;
            case FALLBACK -> hasFallback() ? fallbackKey : primaryKey;
        };
    }

    /**
     * Get the current model name based on provider state.
     */
    public String getCurrentModel() {
        resolveState();
        return switch (state) {
            case PRIMARY, COOLDOWN -> primaryModel;
            case FALLBACK -> hasFallback() ? fallbackModel : primaryModel;
        };
    }

    /** Get the name of the currently active provider. */
    public String getCurrentProviderName() {
        resolveState();
        return switch (state) {
            case PRIMARY -> "openrouter";
            case FALLBACK -> "grok";
            case COOLDOWN -> hasFallback() ? "grok" : "openrouter (cooldown)";
        };
    }

    /**
     * Switch to fallback provider after 429 from primary.
     */
    public void switchToFallback() {
        if (hasFallback()) {
            if (state != ProviderState.FALLBACK) {
                log.warn("Primary provider rate limited (429). Switching to fallback Grok.");
                state = ProviderState.FALLBACK;
            }
        } else {
            log.warn("Primary provider (429) but no fallback configured. Entering cooldown.");
            enterCooldown();
        }
    }

    /**
     * If both providers failed, enter cooldown.
     */
    public void enterCooldown() {
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
        log.warn("AI providers exhausted. Entering cooldown until " +
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(cooldownUntil)) +
            " (hours=" + hours + ")");
    }

    /** Check if currently in cooldown. */
    public boolean isInCooldown() {
        resolveState();
        return state == ProviderState.COOLDOWN;
    }

    /** Get cooldown until timestamp (0 = not in cooldown). */
    public long getCooldownUntil() {
        return cooldownUntil;
    }

    /** Reset to primary provider (e.g., after manual intervention). */
    public void reset() {
        state = ProviderState.PRIMARY;
        cooldownUntil = 0;
        log.info("AI provider reset to primary");
    }

    /** Manual force to fallback. */
    public void forceFallback() {
        if (hasFallback()) {
            state = ProviderState.FALLBACK;
            log.info("AI provider manually switched to fallback (Grok)");
        }
    }

    /** Get current provider state. */
    public ProviderState getState() {
        resolveState();
        return state;
    }

    public String getStateLabel() {
        if (isInCooldown()) {
            return "cooldown-until-" + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(cooldownUntil));
        }
        return switch (state) {
            case PRIMARY -> getProviderLabel(false);
            case FALLBACK -> getProviderLabel(true);
            case COOLDOWN -> "cooldown";
        };
    }

    private String getProviderLabel(boolean isFallback) {
        String url = isFallback ? fallbackUrl : primaryUrl;
        String model = isFallback ? fallbackModel : primaryModel;
        if (model != null && !model.isBlank()) {
            // Short model name
            String[] parts = model.split("/");
            String shortModel = parts[parts.length - 1].replace("-3-mini", "").replace("-2-vision", "");
            return (isFallback ? "grok" : "or") + "/" + shortModel;
        }
        return isFallback ? "grok" : "openrouter";
    }

    private void resolveState() {
        // If configured to use only primary
        if ("primary".equalsIgnoreCase(configuredProvider)) {
            state = ProviderState.PRIMARY;
            return;
        }
        // If configured to use only fallback
        if ("fallback".equalsIgnoreCase(configuredProvider)) {
            state = hasFallback() ? ProviderState.FALLBACK : ProviderState.PRIMARY;
            return;
        }
        // "auto" — use chain logic. Check if cooldown expired.
        if (state == ProviderState.COOLDOWN && System.currentTimeMillis() >= cooldownUntil) {
            state = hasFallback() ? ProviderState.FALLBACK : ProviderState.PRIMARY;
            log.info("Cooldown expired. Switching to " + (hasFallback() ? "fallback" : "primary"));
        }
    }
}
