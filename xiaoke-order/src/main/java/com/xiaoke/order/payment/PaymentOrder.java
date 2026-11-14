package com.xiaoke.order.payment;

import java.math.BigDecimal;

public record PaymentOrder(Long orderId, String paymentNo, String channel, String appId, String sellerId,
                           BigDecimal totalAmount, State status, String tradeNo) {
    public enum State { PENDING, SUCCEEDED, CLOSED }

    public boolean matches(PaymentGateway.Account account) {
        return channel.equals(account.channel()) && appId.equals(account.appId()) && sellerId.equals(account.sellerId());
    }
}
