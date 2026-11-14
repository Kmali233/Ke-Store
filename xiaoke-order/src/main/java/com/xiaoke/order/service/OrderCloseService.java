package com.xiaoke.order.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.mapper.OrdersMapper;
import com.xiaoke.order.mapper.PaymentOrderMapper;
import com.xiaoke.order.payment.MockPaymentAdapter;
import com.xiaoke.order.payment.PaymentOrder;
import com.xiaoke.order.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.xiaoke.order.enums.OrderStatus.*;
import static com.xiaoke.order.service.OrderInput.rows;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCloseService {
    private enum Reason { TIMEOUT, BUYER }
    private record Claim(Long id, String token, PaymentOrder payment) {}
    public record Record(String orderNo, String reason, String resolution, int attempts,
                         LocalDateTime expiresAtUtc, LocalDateTime nextAttemptAtUtc, LocalDateTime finishedAtUtc) {}

    private final OrderTransactions transactions;
    private final OrdersMapper orders;
    private final PaymentOrderMapper payments;
    private final OrderUsers users;
    private final OrderTransactionService writes;
    private final PaymentService paymentService;
    private final OrderInput input;
    private final ObjectProvider<MockPaymentAdapter> mockPayment;

    public void cancel(Long userId, String orderNo) {
        var claim = transactions.write(() -> {
            users.lock(userId);
            var order = orders.lockOwned(userId, orderNo);
            if (order == null) {
                throw new BusinessException(404, "订单不存在");
            }
            var payment = payments.findByOrderId(order.getId());
            if (order.getStatus() != PENDING_PAYMENT.code()) {
                // Preserve historical paid cancellation only for the isolated mock profile.
                var mock = mockPayment.getIfAvailable();
                if (payment == null && mock != null && mock.allowsLegacyPaidCancellation()
                        && (order.getStatus() == PAID.code() || order.getStatus() == PREPARING.code())) {
                    writes.cancelAndRestore(order);
                    return null;
                }
                if (order.getStatus() == PAID.code() || order.getStatus() == PREPARING.code()) {
                    throw new BusinessException(409, "已付款订单请申请整单退款");
                }
                throw new BusinessException(409, "当前订单不可取消");
            }
            if (order.getCloseReason() != null && !due(order)) {
                throw processing();
            }
            var work = claim(order, Reason.BUYER, payment);
            if (payment == null) {
                finish(order, work);
                return null;
            }
            return work;
        });
        if (claim == null) {
            return;
        }
        switch (process(claim)) {
            case PAID -> throw new BusinessException(409, "订单已付款，请申请整单退款");
            case PROCESSING -> throw processing();
            case CLOSED -> { }
        }
    }

    @Scheduled(fixedDelayString = "${xiaoke.order.close-scan-delay-ms:5000}",
            initialDelayString = "${xiaoke.order.close-scan-delay-ms:5000}")
    public void scan() {
        for (Long id : orders.dueForClose()) {
            try {
                var claim = transactions.write(() -> {
                    var order = orders.lockById(id);
                    if (order == null || order.getStatus() != PENDING_PAYMENT.code() || !due(order)) {
                        return null;
                    }
                    return claim(order, Reason.TIMEOUT, payments.findByOrderId(id));
                });
                if (claim != null) {
                    process(claim);
                }
            } catch (RuntimeException failure) {
                log.warn("订单关单处理失败，orderId={}", id, failure);
            }
        }
    }

    public Record record(String orderNo) {
        input.orderNo(orderNo);
        var order = orders.selectForAdmin(orderNo);
        if (order == null || order.getCloseReason() == null) {
            throw new BusinessException(404, "关单处理记录不存在");
        }
        String resolution = order.getStatus() == PENDING_PAYMENT.code() ? "PROCESSING"
                : order.getStatus() == CANCELED.code() ? "CLOSED" : "PAID";
        return new Record(orderNo, order.getCloseReason(), resolution, order.getCloseAttempts(),
                order.getPaymentExpiresAt(), order.getCloseNextAttemptAt(), order.getCloseFinishedAt());
    }

    private boolean due(Orders order) {
        return order.getCloseNextAttemptAt() != null && !order.getCloseNextAttemptAt().isAfter(orders.utcNow());
    }

    private Claim claim(Orders order, Reason reason, PaymentOrder payment) {
        String token = UUID.randomUUID().toString();
        rows(orders.claimClose(order.getId(), reason.name(), token), 1);
        return new Claim(order.getId(), token, payment);
    }

    private enum Outcome { CLOSED, PAID, PROCESSING }

    private Outcome process(Claim claim) {
        if (claim.payment() != null) {
            try {
                switch (paymentService.coordinateClose(claim.payment())) {
                    case PAID -> { return Outcome.PAID; }
                    case UNKNOWN -> { return Outcome.PROCESSING; }
                    case CLOSED -> { }
                }
            } catch (BusinessException failure) {
                log.warn("支付通道关单结果待重查，orderId={}, code={}", claim.id(), failure.getCode());
                return Outcome.PROCESSING;
            }
        }
        return transactions.write(() -> {
            var order = orders.lockById(claim.id());
            if (order == null) {
                return Outcome.PROCESSING;
            }
            if (order.getStatus() == CANCELED.code()) {
                return Outcome.CLOSED;
            }
            if (order.getStatus() != PENDING_PAYMENT.code()) {
                return Outcome.PAID;
            }
            if (!claim.token().equals(order.getCloseToken())) {
                return Outcome.PROCESSING;
            }
            finish(order, claim);
            return Outcome.CLOSED;
        });
    }

    private void finish(Orders order, Claim claim) {
        if (claim.payment() != null) {
            rows(payments.close(order.getId()), 1);
        }
        writes.cancelAndRestore(order);
        rows(orders.finishClose(order.getId()), 1);
    }

    private static BusinessException processing() {
        return new BusinessException(409, "订单关单处理中，请稍后查询");
    }
}
