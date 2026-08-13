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

    /**
     * Active means paid AND not past expiresAt. This used to check the status alone, so a
     * subscription stayed "active" forever once activated: nothing ever moved it off that
     * status, findActiveChatIds kept broadcasting to it, and subscribe() refused to start
     * a new checkout because it already looked active. One payment bought permanent access.
     */
    public boolean isActive() { return STATUS_ACTIVE.equals(status) && !isExpired(); }

    /**
     * A missing or unparsable expiresAt counts as expired: activate() always records one,
     * so its absence means we cannot prove the subscription is still paid for — and access
     * to a paid feed is the wrong thing to grant on an unreadable value.
     */
    public boolean isExpired() {
        if (expiresAt == null || expiresAt.isBlank()) return true;
        try {
            return !java.time.Instant.parse(expiresAt).isAfter(java.time.Instant.now());
        } catch (java.time.format.DateTimeParseException e) {
            return true;
        }
    }
}
