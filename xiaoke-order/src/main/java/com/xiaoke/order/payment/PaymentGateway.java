package com.xiaoke.order.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

public interface PaymentGateway {
    record Account(String channel, String appId, String sellerId) {}

    enum TradeState {
        WAIT_BUYER_PAY, TRADE_SUCCESS, TRADE_FINISHED, TRADE_CLOSED;

        public boolean paid() {
            return this == TRADE_SUCCESS || this == TRADE_FINISHED;
        }
    }

    record Trade(Account account, String paymentNo, String tradeNo, String totalAmount, TradeState state) {}

    enum RefundState { PROCESSING, SUCCEEDED, FAILED }

    record RefundTrade(Account account, String paymentNo, String tradeNo, String refundNo,
                       BigDecimal amount, RefundState state) {}

    Account account();
    String checkout(PaymentOrder payment, String orderNo, LocalDateTime expiresAtUtc);
    boolean closeUnpaid(PaymentOrder payment);
    Optional<Trade> query(PaymentOrder payment);
    void submitRefund(PaymentOrder payment, String refundNo, BigDecimal amount);
    Optional<RefundTrade> queryRefund(PaymentOrder payment, String refundNo);
    Trade verifyNotification(Map<String, String> parameters);
}
