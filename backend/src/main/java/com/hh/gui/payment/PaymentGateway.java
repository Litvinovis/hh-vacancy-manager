package com.hh.gui.payment;

/**
 * Abstraction over "start a checkout for this Telegram user". The only
 * implementation right now is {@link StubPaymentGateway} — see its javadoc for
 * why a real provider (YooKassa/Robokassa) isn't wired in yet. Swapping one in
 * later means implementing this interface and registering it in place of the
 * stub; nothing above this layer (bot commands, SubscriptionService) needs to change.
 */
public interface PaymentGateway {

    CheckoutResult createCheckout(long telegramUserId, int amountRub);

    /**
     * @param available    false if no real provider is configured — checkoutUrl is
     *                      then null and message explains why to show the user.
     * @param checkoutUrl  link to send the user to pay, when available is true.
     * @param message      human-readable status, always safe to show the user directly.
     */
    record CheckoutResult(boolean available, String checkoutUrl, String message) {}
}
