package com.hh.gui.payment;

import org.springframework.stereotype.Component;

/**
 * Default (and, for now, only) PaymentGateway — no real provider is configured yet.
 * Deliberately honest rather than half-wired: a real YooKassa/Robokassa integration
 * needs live shop_id/secret_key to test the checkout+webhook round-trip safely, which
 * this deployment doesn't have. /subscribe therefore always tells the user payment
 * isn't available yet instead of pretending to start a checkout that can't complete.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public CheckoutResult createCheckout(long telegramUserId, int amountRub) {
        return new CheckoutResult(false, null,
            "Оплата подписки скоро появится — платёжный провайдер ещё не подключён. Мы напишем, когда всё будет готово.");
    }
}
