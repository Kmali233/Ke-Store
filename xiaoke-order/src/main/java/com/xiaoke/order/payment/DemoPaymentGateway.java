package com.xiaoke.order.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static com.xiaoke.order.payment.PaymentNotification.RSA2;
import static com.xiaoke.order.payment.PaymentNotification.UTF8;

@Component
@Profile(DemoPaymentGateway.PROFILE)
public class DemoPaymentGateway implements PaymentGateway {
    public static final String PROFILE = "payment-demo & !prod & !payment-test & !alipay-sandbox";
    public static final String CHANNEL = "DEMO";
    private static final Account ACCOUNT = new Account(CHANNEL, "xiaoke-demo", "xiaoke-demo-seller");
    private final JdbcTemplate jdbc;
    private final String privateKey;
    private final String publicKey;

    public DemoPaymentGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (schema == null || !schema.matches("xiaoke_demo_[a-z0-9_]{1,52}")) {
            throw new IllegalStateException("演示支付只允许连接 xiaoke_demo_ 前缀的专用数据库");
        }
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keys = generator.generateKeyPair();
            var base64 = Base64.getEncoder();
            privateKey = base64.encodeToString(keys.getPrivate().getEncoded());
            publicKey = base64.encodeToString(keys.getPublic().getEncoded());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法初始化演示支付测试密钥", ex);
        }
    }

    @Override
    public Account account() {
        return ACCOUNT;
    }

    @Override
    public String checkout(PaymentOrder payment, String orderNo, LocalDateTime expiresAtUtc) {
        return "/orders/" + orderNo + "/demo-checkout";
    }

    @Override
    public boolean closeUnpaid(PaymentOrder payment) {
        // The committed order claim fences new demo receipts under the same order row lock.
        return query(payment).isEmpty();
    }

    void recordPaid(PaymentOrder payment) {
        jdbc.update("""
                INSERT INTO t_demo_payment_trade(payment_no) VALUES (?)
                ON DUPLICATE KEY UPDATE payment_no=payment_no
                """, payment.paymentNo());
    }

    @Override
    public Optional<Trade> query(PaymentOrder payment) {
        boolean paid = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM t_demo_payment_trade WHERE payment_no=?)",
                Boolean.class, payment.paymentNo()));
        return paid ? Optional.of(trade(payment)) : Optional.empty();
    }

    @Override
    public void submitRefund(PaymentOrder payment, String refundNo, BigDecimal amount) {
        if (query(payment).isEmpty()) {
            throw PaymentNotification.invalidTrade();
        }
        jdbc.update("""
                INSERT INTO t_demo_refund_trade(payment_no,refund_no,amount) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE payment_no=payment_no
                """, payment.paymentNo(), refundNo, amount);
    }

    @Override
    public Optional<RefundTrade> queryRefund(PaymentOrder payment, String refundNo) {
        return jdbc.query("""
                SELECT payment_no,refund_no,amount FROM t_demo_refund_trade WHERE payment_no=?
                """, (row, index) -> new RefundTrade(ACCOUNT, row.getString("payment_no"),
                trade(payment).tradeNo(), row.getString("refund_no"), row.getBigDecimal("amount"),
                RefundState.SUCCEEDED), payment.paymentNo()).stream().findFirst();
    }

    Map<String, String> signedNotification(PaymentOrder payment) {
        var trade = trade(payment);
        var fields = new HashMap<>(Map.of(
                "app_id", ACCOUNT.appId(), "seller_id", ACCOUNT.sellerId(),
                "out_trade_no", trade.paymentNo(), "trade_no", trade.tradeNo(),
                "total_amount", trade.totalAmount(), "trade_status", trade.state().name()));
        try {
            String content = AlipaySignature.getSignCheckContentV1(new HashMap<>(fields));
            fields.put("sign", AlipaySignature.rsaSign(content, privateKey, UTF8, RSA2));
            fields.put("sign_type", RSA2);
            return fields;
        } catch (AlipayApiException ex) {
            throw new IllegalStateException("演示支付通知签名失败", ex);
        }
    }

    @Override
    public Trade verifyNotification(Map<String, String> parameters) {
        return PaymentNotification.verify(parameters, publicKey, ACCOUNT);
    }

    private Trade trade(PaymentOrder payment) {
        // The payment number gives this simulated provider a stable numeric transaction ID.
        String tradeNo = new BigInteger(payment.paymentNo(), 16).toString(10);
        return new Trade(ACCOUNT, payment.paymentNo(), tradeNo, payment.totalAmount().toPlainString(),
                TradeState.TRADE_SUCCESS);
    }
}
