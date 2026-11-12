package com.xiaoke.order.payment;

import java.util.Map;
import java.util.Optional;

public interface PaymentGateway {
    record Account(String channel, String appId, String sellerId) {}

    enum TradeState {
        WAIT_BUYER_PAY, TRADE_SUCCESS, TRADE_FINISHED, TRADE_CLOSED;

        boolean paid() {
            return this == TRADE_SUCCESS || this == TRADE_FINISHED;
        }
    }

    record Trade(Account account, String paymentNo, String tradeNo, String totalAmount, TradeState state) {}

    Account account();
    String checkout(PaymentOrder payment, String orderNo);
    Optional<Trade> query(PaymentOrder payment);
    Trade verifyNotification(Map<String, String> parameters);
}
