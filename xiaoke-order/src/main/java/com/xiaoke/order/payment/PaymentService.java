package com.xiaoke.order.payment;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.enums.OrderStatus;
import com.xiaoke.order.mapper.OrdersMapper;
import com.xiaoke.order.mapper.PaymentOrderMapper;
import com.xiaoke.order.service.OrderInput;
import com.xiaoke.order.service.OrderTransactions;
import com.xiaoke.order.service.OrderUsers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.xiaoke.order.payment.PaymentOrder.State.PENDING;
import static com.xiaoke.order.payment.PaymentOrder.State.SUCCEEDED;
import static com.xiaoke.order.payment.PaymentNotification.invalidTrade;
import static com.xiaoke.order.service.OrderInput.rows;

@Service
@RequiredArgsConstructor
public class PaymentService {
    public record Status(boolean sandboxEnabled, boolean demoEnabled, boolean canStart, String state, String channel,
                         boolean closeProcessing, boolean paymentExpired, boolean canSimulate) {}
    public record Checkout(String paymentUrl) {}
    private record CheckoutRequest(PaymentOrder payment, LocalDateTime expiresAtUtc) {}
    public enum CloseResult { PAID, CLOSED, UNKNOWN }

    private final ObjectProvider<PaymentGateway> gatewayProvider;
    private final ObjectProvider<MockPaymentAdapter> mockPayment;
    private final OrdersMapper orders;
    private final PaymentOrderMapper payments;
    private final OrderUsers users;
    private final OrderInput input;
    private final OrderTransactions transactions;

    public Checkout start(Long userId, String orderNo, String channel) {
        input.orderNo(orderNo);
        String expectedChannel = switch (channel) {
            case "alipay" -> AlipaySandboxGateway.CHANNEL;
            case "demo" -> DemoPaymentGateway.CHANNEL;
            case "wechat" -> throw new BusinessException(409, "微信支付通道尚未启用");
            default -> throw new BusinessException(400, "支付通道不合法");
        };
        var gateway = requireGateway(expectedChannel);
        var checkout = transactions.write(() -> {
            users.lock(userId);
            var order = requireOrder(orders.lockOwned(userId, orderNo));
            if (!isPending(order) || !order.acceptsPayment(orders.utcNow()) || order.getTotalAmount().signum() <= 0) {
                throw new BusinessException(409, "当前订单不可发起支付");
            }
            var previous = payments.findByOrderId(order.getId());
            if (previous != null) {
                requireAccount(previous, gateway.account());
                if (previous.status() != PENDING) {
                    throw new BusinessException(409, "支付单已经确认");
                }
                return new CheckoutRequest(previous, order.getPaymentExpiresAt());
            }
            var account = gateway.account();
            var created = new PaymentOrder(order.getId(), UUID.randomUUID().toString().replace("-", ""),
                    account.channel(), account.appId(), account.sellerId(), order.getTotalAmount(), PENDING, null);
            rows(payments.insert(created), 1);
            return new CheckoutRequest(created, order.getPaymentExpiresAt());
        });
        // The payment number must be committed before the buyer can reach the gateway.
        return new Checkout(gateway.checkout(checkout.payment(), orderNo, checkout.expiresAtUtc()));
    }

    public Status status(Long userId, String orderNo) {
        input.orderNo(orderNo);
        return transactions.read(() -> {
            users.check(userId);
            var order = requireOrder(orders.selectOwned(userId, orderNo));
            var payment = payments.findByOrderId(order.getId());
            var gateway = gatewayProvider.getIfAvailable();
            var now = orders.utcNow();
            var mock = mockPayment.getIfAvailable();
            boolean pendingAndPayable = isPending(order) && order.acceptsPayment(now);
            boolean canSimulate = mock != null && mock.isLocalSimulation() && pendingAndPayable && payment == null;
            boolean canStart = gateway != null && pendingAndPayable && order.getTotalAmount().signum() > 0
                    && (payment == null || (payment.status() == PENDING && payment.matches(gateway.account())));
            String enabled = gateway == null ? null : gateway.account().channel();
            return new Status(AlipaySandboxGateway.CHANNEL.equals(enabled), DemoPaymentGateway.CHANNEL.equals(enabled),
                    canStart, payment == null ? "NONE" : payment.status().name(),
                    payment == null ? enabled : payment.channel(),
                    isPending(order) && order.getCloseReason() != null, isPending(order) && order.paymentExpired(now), canSimulate);
        });
    }

    public Status query(Long userId, String orderNo) {
        input.orderNo(orderNo);
        users.check(userId);
        var order = requireOrder(orders.selectOwned(userId, orderNo));
        var payment = payments.findByOrderId(order.getId());
        if (payment != null && payment.status() == PENDING) {
            var gateway = requireGateway(payment.channel());
            requireAccount(payment, gateway.account());
            // A slow remote query never holds the same order lock as the notification or cancellation.
            gateway.query(payment).ifPresent(trade -> {
                if (!payment.paymentNo().equals(trade.paymentNo())) {
                    throw invalidTrade();
                }
                confirm(trade);
            });
        }
        return status(userId, orderNo);
    }

    public void notifyAlipay(Map<String, String> parameters) {
        confirm(requireGateway(AlipaySandboxGateway.CHANNEL).verifyNotification(parameters));
    }

    public void payDemo(Long userId, String orderNo, boolean deliverNotification) {
        input.orderNo(orderNo);
        if (!(gatewayProvider.getIfAvailable() instanceof DemoPaymentGateway demo)) {
            throw new BusinessException(409, "演示支付通道未启用");
        }
        var payment = transactions.write(() -> {
            users.lock(userId);
            var order = requireOrder(orders.lockOwned(userId, orderNo));
            var existing = payments.findByOrderId(order.getId());
            if (existing == null) {
                throw new BusinessException(409, "请先发起演示支付");
            }
            requireAccount(existing, demo.account());
            if (existing.status() != SUCCEEDED && (existing.status() != PENDING || !isPending(order)
                    || !order.acceptsPayment(orders.utcNow()))) {
                throw new BusinessException(409, "当前订单不可付款");
            }
            demo.recordPaid(existing);
            return existing;
        });
        if (deliverNotification) {
            confirm(demo.verifyNotification(demo.signedNotification(payment)));
        }
    }

    public CloseResult coordinateClose(PaymentOrder payment) {
        transactions.outsideTransaction();
        var gateway = requireGateway(payment.channel());
        requireAccount(payment, gateway.account());
        var trade = gateway.query(payment).orElse(null);
        if (trade != null) {
            if (!payment.paymentNo().equals(trade.paymentNo()) || !payment.matches(trade.account())) {
                throw invalidTrade();
            }
            if (trade.state().paid()) {
                confirm(trade);
                return CloseResult.PAID;
            }
            if (trade.state() == PaymentGateway.TradeState.TRADE_CLOSED) {
                return CloseResult.CLOSED;
            }
        } else if (!DemoPaymentGateway.CHANNEL.equals(payment.channel())) {
            // A previously issued page-pay URL might still create the missing trade.
            return CloseResult.UNKNOWN;
        }
        return gateway.closeUnpaid(payment) ? CloseResult.CLOSED : CloseResult.UNKNOWN;
    }

    public void confirm(PaymentGateway.Trade trade) {
        if (trade.paymentNo() == null || !trade.paymentNo().matches("[a-f0-9]{32}")
                || trade.totalAmount() == null || !trade.totalAmount().matches("[0-9]{1,9}(\\.[0-9]{1,2})?")
                || (trade.state().paid() && (trade.tradeNo() == null || !trade.tradeNo().matches("[0-9]{1,64}")))) {
            throw invalidTrade();
        }
        var amount = new BigDecimal(trade.totalAmount());
        transactions.write(() -> {
            Long orderId = payments.findOrderId(trade.paymentNo());
            if (orderId == null) {
                throw invalidTrade();
            }
            var order = requireOrder(orders.lockById(orderId));
            var payment = payments.findByOrderId(orderId);
            if (payment == null || !payment.paymentNo().equals(trade.paymentNo()) || !payment.matches(trade.account())
                    || payment.totalAmount().compareTo(amount) != 0 || order.getTotalAmount().compareTo(amount) != 0) {
                throw invalidTrade();
            }
            if (!trade.state().paid()) {
                return null;
            }
            if (payment.status() == SUCCEEDED) {
                if (!trade.tradeNo().equals(payment.tradeNo())) {
                    throw invalidTrade();
                }
                return null;
            }
            if (!isPending(order)) {
                throw new BusinessException(409, "订单状态与支付结果冲突");
            }
            rows(orders.casPay(order.getId(), order.getUserId()), 1);
            rows(payments.succeed(orderId, trade.tradeNo()), 1);
            if (order.getCloseReason() != null) {
                rows(orders.finishClose(orderId), 1);
            }
            return null;
        });
    }

    public PaymentGateway gatewayFor(PaymentOrder payment) {
        var gateway = requireGateway(payment.channel());
        requireAccount(payment, gateway.account());
        return gateway;
    }

    private PaymentGateway requireGateway(String channel) {
        var gateway = gatewayProvider.getIfAvailable();
        if (gateway == null || !gateway.account().channel().equals(channel)) {
            throw new BusinessException(409, "该支付通道未启用");
        }
        return gateway;
    }

    private static void requireAccount(PaymentOrder payment, PaymentGateway.Account account) {
        if (!payment.matches(account)) {
            throw new BusinessException(409, "当前支付应用或商户与原支付单不一致");
        }
    }

    private static Orders requireOrder(Orders order) {
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private static boolean isPending(Orders order) {
        return Integer.valueOf(OrderStatus.PENDING_PAYMENT.code()).equals(order.getStatus());
    }
}
