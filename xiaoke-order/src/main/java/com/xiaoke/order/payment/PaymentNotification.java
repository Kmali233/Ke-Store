package com.xiaoke.order.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.xiaoke.common.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;

final class PaymentNotification {
    static final String UTF8 = "UTF-8";
    static final String RSA2 = "RSA2";

    private PaymentNotification() {}

    static PaymentGateway.Trade verify(Map<String, String> parameters, String publicKey,
                                       PaymentGateway.Account account) {
        try {
            if (!RSA2.equals(parameters.get("sign_type")) || !parameters.containsKey("sign")
                    || !AlipaySignature.rsaCheckV1(new HashMap<>(parameters), publicKey, UTF8, RSA2)) {
                throw invalidTrade();
            }
        } catch (AlipayApiException ex) {
            throw invalidTrade();
        }
        var notifiedAccount = new PaymentGateway.Account(account.channel(), parameters.get("app_id"),
                parameters.get("seller_id"));
        if (!account.equals(notifiedAccount)) {
            throw invalidTrade();
        }
        return new PaymentGateway.Trade(notifiedAccount, parameters.get("out_trade_no"), parameters.get("trade_no"),
                parameters.get("total_amount"), parseState(parameters.get("trade_status")));
    }

    static PaymentGateway.TradeState parseState(String value) {
        try {
            return PaymentGateway.TradeState.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ex) {
            throw invalidTrade();
        }
    }

    static BusinessException invalidTrade() {
        return new BusinessException(400, "支付交易信息校验失败");
    }
}
