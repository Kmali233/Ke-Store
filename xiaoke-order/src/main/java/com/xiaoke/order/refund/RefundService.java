package com.xiaoke.order.refund;

import com.xiaoke.audit.AuditAction;
import com.xiaoke.audit.AuditChanges;
import com.xiaoke.audit.AuditWriter;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.mapper.OrdersMapper;
import com.xiaoke.order.mapper.PaymentOrderMapper;
import com.xiaoke.order.payment.PaymentGateway;
import com.xiaoke.order.payment.PaymentOrder;
import com.xiaoke.order.payment.PaymentService;
import com.xiaoke.order.service.OrderInput;
import com.xiaoke.order.service.OrderTransactionService;
import com.xiaoke.order.service.OrderTransactions;
import com.xiaoke.order.service.OrderUsers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.xiaoke.audit.AuditChanges.Field.STATUS;
import static com.xiaoke.order.enums.OrderStatus.PAID;
import static com.xiaoke.order.enums.OrderStatus.PREPARING;
import static com.xiaoke.order.refund.RefundOrder.State.*;
import static com.xiaoke.order.service.OrderInput.rows;

@Service
@RequiredArgsConstructor
public class RefundService {
    public record Status(boolean canRequest, String refundNo, BigDecimal amount, RefundOrder.State status,
                         LocalDateTime updatedAt) {}
    private record Review(PaymentOrder payment, RefundOrder refund, boolean submit) {}

    private final RefundMapper refunds;
    private final OrdersMapper orders;
    private final PaymentOrderMapper payments;
    private final OrderUsers users;
    private final OrderInput input;
    private final OrderTransactions transactions;
    private final OrderTransactionService writes;
    private final PaymentService paymentService;
    private final AuditWriter audit;

    public Status status(long userId, String orderNo) {
        input.orderNo(orderNo);
        return transactions.read(() -> {
            users.check(userId);
            return snapshot(requireOrder(orders.selectOwned(userId, orderNo)));
        });
    }

    public Status request(long userId, String orderNo) {
        input.orderNo(orderNo);
        return transactions.write(() -> {
            users.lock(userId);
            var order = requireOrder(orders.lockOwned(userId, orderNo));
            var before = snapshot(order);
            if (before.status() != null) {
                return before;
            }
            if (!before.canRequest()) {
                throw new BusinessException(409, "当前订单不可申请整单退款");
            }
            var payment = payments.findByOrderId(order.getId());
            rows(refunds.request(order.getId(), UUID.randomUUID().toString().replace("-", ""),
                    payment.totalAmount()), 1);
            return snapshot(order);
        });
    }

    public List<RefundMapper.Summary> list(RefundOrder.State status, String orderNo, int page) {
        if (orderNo != null) {
            input.orderNo(orderNo);
        }
        if (page < 1) {
            throw new BusinessException(400, "页码须大于零");
        }
        return refunds.list(status, orderNo, (page - 1L) * 50);
    }

    public Status review(String orderNo, boolean approve) {
        input.orderNo(orderNo);
        var review = transactions.write(() -> {
            var order = requireOrder(orders.lockByNo(orderNo));
            var refund = refunds.find(order.getId());
            if (refund == null) {
                throw new BusinessException(404, "退款申请不存在");
            }
            if (refund.status() == REQUESTED) {
                var next = approve ? PROCESSING : REJECTED;
                rows(refunds.transition(order.getId(), REQUESTED, next), 1);
                audit.record(AuditAction.REFUND_REVIEW, orderNo,
                        AuditChanges.start().put(STATUS, REQUESTED.ordinal(), next.ordinal()));
                return new Review(payments.findByOrderId(order.getId()), refunds.find(order.getId()), approve);
            }
            if (approve == (refund.status() == REJECTED)) {
                throw new BusinessException(409, "退款申请已审核");
            }
            return new Review(payments.findByOrderId(order.getId()), refund, false);
        });
        if (review.submit()) {
            submitAndQuery(review.payment(), review.refund());
        }
        return snapshot(requireOrder(orders.selectForAdmin(orderNo)));
    }

    public Status retry(String orderNo) {
        input.orderNo(orderNo);
        var review = transactions.write(() -> {
            var order = requireOrder(orders.lockByNo(orderNo));
            var refund = refunds.find(order.getId());
            if (refund == null || refund.status() != PROCESSING) {
                throw new BusinessException(409, "仅处理中退款可重试原请求");
            }
            audit.record(AuditAction.REFUND_RETRY, orderNo, AuditChanges.start().put(STATUS, null, 1));
            return new Review(payments.findByOrderId(order.getId()), refund, false);
        });
        var current = queryChannel(review.payment(), review.refund());
        if (current.isPresent()) {
            confirm(review.payment(), review.refund(), current.get());
        } else {
            submitAndQuery(review.payment(), review.refund());
        }
        return snapshot(requireOrder(orders.selectForAdmin(orderNo)));
    }

    public Optional<PaymentGateway.RefundTrade> queryChannel(PaymentOrder payment, RefundOrder refund) {
        transactions.outsideTransaction();
        return paymentService.gatewayFor(payment).queryRefund(payment, refund.refundNo());
    }

    public void confirm(PaymentOrder payment, RefundOrder refund, PaymentGateway.RefundTrade trade) {
        if (!matchesIdentity(payment, refund, trade) || !matchesAmount(refund, trade)) {
            throw new BusinessException(409, "通道退款身份或金额与原退款单不一致");
        }
        transactions.write(() -> {
            var order = requireOrder(orders.lockById(refund.orderId()));
            var current = refunds.find(order.getId());
            var localPayment = payments.findByOrderId(order.getId());
            if (current == null || !current.refundNo().equals(refund.refundNo()) || localPayment == null
                    || !matchesIdentity(localPayment, current, trade)
                    || !matchesAmount(current, trade)
                    || order.getTotalAmount().compareTo(current.amount()) != 0
                    || localPayment.totalAmount().compareTo(current.amount()) != 0) {
                throw new BusinessException(409, "退款单与本地交易不一致");
            }
            if (current.status() == SUCCEEDED) {
                return null;
            }
            if (current.status() != PROCESSING && current.status() != FAILED) {
                throw new BusinessException(409, "退款申请尚未通过审核");
            }
            switch (trade.state()) {
                case SUCCEEDED -> {
                    writes.refundAndRestore(order);
                    rows(refunds.transition(order.getId(), current.status(), SUCCEEDED), 1);
                }
                case FAILED -> {
                    if (current.status() != FAILED) {
                        rows(refunds.transition(order.getId(), PROCESSING, FAILED), 1);
                    }
                }
                case PROCESSING -> { }
            }
            return null;
        });
    }

    public boolean matchesIdentity(PaymentOrder payment, RefundOrder refund, PaymentGateway.RefundTrade trade) {
        return payment.status() == PaymentOrder.State.SUCCEEDED && payment.matches(trade.account())
                && payment.paymentNo().equals(trade.paymentNo()) && refund.refundNo().equals(trade.refundNo())
                && payment.tradeNo() != null && payment.tradeNo().equals(trade.tradeNo());
    }

    public boolean matchesAmount(RefundOrder refund, PaymentGateway.RefundTrade trade) {
        return trade.amount() == null ? trade.state() != PaymentGateway.RefundState.SUCCEEDED
                : refund.amount().compareTo(trade.amount()) == 0;
    }

    private void submitAndQuery(PaymentOrder payment, RefundOrder refund) {
        try {
            paymentService.gatewayFor(payment).submitRefund(payment, refund.refundNo(), refund.amount());
        } catch (BusinessException failure) {
            if (failure.getCode() != 503) {
                throw failure;
            }
            // The provider may have accepted the request before the connection failed.
        }
        queryChannel(payment, refund).ifPresent(trade -> confirm(payment, refund, trade));
    }

    private Status snapshot(Orders order) {
        var refund = refunds.find(order.getId());
        if (refund != null) {
            return new Status(false, refund.refundNo(), refund.amount(), refund.status(), refund.updatedAt());
        }
        var payment = payments.findByOrderId(order.getId());
        boolean canRequest = (order.getStatus() == PAID.code() || order.getStatus() == PREPARING.code())
                && payment != null && payment.status() == PaymentOrder.State.SUCCEEDED
                && payment.totalAmount().signum() > 0 && payment.totalAmount().compareTo(order.getTotalAmount()) == 0;
        return new Status(canRequest, null, null, null, null);
    }

    private Orders requireOrder(Orders order) {
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }
}
