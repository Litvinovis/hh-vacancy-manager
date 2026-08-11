package com.hh.gui.model;

/**
 * One paid early-access subscriber of the Telegram bot (see FeatureFlags.subscriptionsEnabled).
 * telegram_user_id identifies the person; telegram_chat_id is where broadcasts land —
 * for a private bot chat the two happen to have the same numeric value, but they're
 * kept separate since Telegram doesn't guarantee that in general.
 */
public class Subscription {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CANCELLED = "cancelled";

    private Long id;
    private long telegramUserId;
    private long telegramChatId;
    private String status = STATUS_PENDING;
    private int planPriceRub = 200;
    private String startedAt;
    private String expiresAt;
    private String paymentProvider = "stub";
    private String externalPaymentId;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(long telegramUserId) { this.telegramUserId = telegramUserId; }

    public long getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(long telegramChatId) { this.telegramChatId = telegramChatId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPlanPriceRub() { return planPriceRub; }
    public void setPlanPriceRub(int planPriceRub) { this.planPriceRub = planPriceRub; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }

    public String getExternalPaymentId() { return externalPaymentId; }
    public void setExternalPaymentId(String externalPaymentId) { this.externalPaymentId = externalPaymentId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
