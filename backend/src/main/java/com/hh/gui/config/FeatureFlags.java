package com.hh.gui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single switchboard for the Telegram-channel/paid-subscription work in progress
 * (public channel, delayed dual-publish, paid early access). All default to
 * disabled so the code can ship inert and be flipped on independently, per piece,
 * once its prerequisites (bot admin on the channel, payment provider keys) are
 * actually in place — see the app.telegram and app.subscriptions keys in application.yml.
 */
@Component
public class FeatureFlags {

    @Value("${app.telegram.public-format-enabled:false}")
    private boolean publicFormatEnabled;

    @Value("${app.telegram.delayed-publish-enabled:false}")
    private boolean delayedPublishEnabled;

    @Value("${app.subscriptions.enabled:false}")
    private boolean subscriptionsEnabled;

    public boolean isPublicFormatEnabled() { return publicFormatEnabled; }
    public boolean isDelayedPublishEnabled() { return delayedPublishEnabled; }
    public boolean isSubscriptionsEnabled() { return subscriptionsEnabled; }
}
