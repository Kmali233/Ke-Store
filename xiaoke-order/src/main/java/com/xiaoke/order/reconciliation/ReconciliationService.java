package com.xiaoke.order.reconciliation;

import com.xiaoke.audit.AuditAction;
import com.xiaoke.audit.AuditChanges;
import com.xiaoke.audit.AuditWriter;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.mapper.OrdersMapper;
import com.xiaoke.order.mapper.PaymentOrderMapper;
import com.xiaoke.order.payment.DemoPaymentGateway;
import com.xiaoke.order.payment.PaymentGateway;
import com.xiaoke.order.payment.PaymentOrder;
import com.xiaoke.order.payment.PaymentService;
import com.xiaoke.order.refund.RefundMapper;
import com.xiaoke.order.refund.RefundOrder;
import com.xiaoke.order.refund.RefundService;
import com.xiaoke.order.service.OrderInput;
import com.xiaoke.order.service.OrderTransactions;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.xiaoke.audit.AuditChanges.Field.STATUS;
import static com.xiaoke.order.enums.OrderStatus.CANCELED;
import static com.xiaoke.order.enums.OrderStatus.PENDING_PAYMENT;
import static com.xiaoke.order.reconciliation.ReconciliationMapper.*;
import static com.xiaoke.order.reconciliation.ReconciliationMapper.Outcome.*;

@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private final ReconciliationMapper records;
    private final OrdersMapper orders;
    private final PaymentOrderMapper payments;
    private final RefundMapper refunds;
    private final OrderInput input;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final OrderTransactions transactions;
    private final AuditWriter audit;

    public List<Entry> list(String orderNo, boolean differences, int page) {
        if (orderNo != null) {
            input.orderNo(orderNo);
        }
        if (page < 1) {
            throw new BusinessException(400, "页码须大于零");
        }
        return records.list(orderNo, differences, (page - 1L) * 50);
    }

    public Entry recheck(String orderNo, Kind kind) {
        input.orderNo(orderNo);
        var candidate = records.find(orderNo, kind);
        if (candidate == null) {
            throw new BusinessException(404, "交易单据不存在");
        }
        return check(candidate);
    }

    public List<Entry> runBatch() {
        var results = new ArrayList<Entry>();
        for (var candidate : records.nextBatch()) {
            results.add(check(candidate));
        }
        return results;
    }

    private Entry check(Candidate candidate) {
        var order = orders.selectForAdmin(candidate.orderNo());
        var payment = payments.findByOrderId(candidate.orderId());
        var outcome = candidate.kind() == Kind.PAYMENT
                ? checkPayment(order, payment) : checkRefund(payment, refunds.find(candidate.orderId()));
        return transactions.write(() -> {
            records.save(candidate.orderId(), candidate.kind(), outcome);
            var action = candidate.kind() == Kind.PAYMENT ? AuditAction.PAYMENT_RECONCILE : AuditAction.REFUND_RECONCILE;
            audit.record(action, candidate.orderNo(), AuditChanges.start().put(STATUS, null, outcome.ordinal()));
            return records.entry(candidate.orderId(), candidate.kind());
        });
    }

    private Outcome checkPayment(Orders order, PaymentOrder payment) {
        PaymentGateway.Trade trade;
        try {
            trade = paymentService.gatewayFor(payment).query(payment).orElse(null);
        } catch (BusinessException failure) {
            return queryFailure(failure);
        }
        if (trade == null) {
            return payment.status() == PaymentOrder.State.CLOSED && DemoPaymentGateway.CHANNEL.equals(payment.channel())
                    ? MATCHED : NOT_FOUND;
        }
        if (!payment.paymentNo().equals(trade.paymentNo()) || !payment.matches(trade.account())) {
            return IDENTITY_MISMATCH;
        }
        if (trade.totalAmount() == null || !trade.totalAmount().matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) {
            return AMOUNT_MISMATCH;
        }
        var amount = new BigDecimal(trade.totalAmount());
        if (payment.totalAmount().compareTo(amount) != 0 || order.getTotalAmount().compareTo(amount) != 0) {
            return AMOUNT_MISMATCH;
        }
        if (trade.state() == PaymentGateway.TradeState.TRADE_CLOSED
                && payment.status() == PaymentOrder.State.SUCCEEDED) {
            var refund = refunds.find(order.getId());
            if (refund != null && refund.status() == RefundOrder.State.SUCCEEDED && order.getStatus() == CANCELED.code()) {
                return payment.tradeNo().equals(trade.tradeNo()) ? MATCHED : DUPLICATE_TRADE;
            }
        }
        if (!trade.state().paid()) {
            if (payment.status() == PaymentOrder.State.CLOSED
                    && trade.state() == PaymentGateway.TradeState.TRADE_CLOSED) {
                return MATCHED;
            }
            return payment.status() == PaymentOrder.State.PENDING
                    && trade.state() == PaymentGateway.TradeState.WAIT_BUYER_PAY ? PROCESSING : STATE_MISMATCH;
        }
        if (trade.tradeNo() == null || !trade.tradeNo().matches("[0-9]{1,64}")) {
            return IDENTITY_MISMATCH;
        }
        Long recordedOwner = payments.findTradeOwner(payment.channel(), trade.tradeNo());
        if ((recordedOwner != null && !recordedOwner.equals(payment.orderId()))
                || (payment.tradeNo() != null && !payment.tradeNo().equals(trade.tradeNo()))) {
            return DUPLICATE_TRADE;
        }
        if (payment.status() == PaymentOrder.State.SUCCEEDED) {
            return order.getStatus() == PENDING_PAYMENT.code() ? STATE_MISMATCH : MATCHED;
        }
        if (payment.status() != PaymentOrder.State.PENDING || order.getStatus() != PENDING_PAYMENT.code()) {
            return STATE_MISMATCH;
        }
        try {
            paymentService.confirm(trade);
            return REPAIRED;
        } catch (DuplicateKeyException failure) {
            return DUPLICATE_TRADE;
        } catch (BusinessException failure) {
            return APPLY_FAILED;
        }
    }

    private Outcome checkRefund(PaymentOrder payment, RefundOrder refund) {
        PaymentGateway.RefundTrade trade;
        try {
            trade = refundService.queryChannel(payment, refund).orElse(null);
        } catch (BusinessException failure) {
            return queryFailure(failure);
        }
        boolean unapproved = refund.status() == RefundOrder.State.REQUESTED
                || refund.status() == RefundOrder.State.REJECTED;
        if (trade == null) {
            return unapproved ? MATCHED : NOT_FOUND;
        }
        if (!refundService.matchesIdentity(payment, refund, trade)) {
            return IDENTITY_MISMATCH;
        }
        if (!refundService.matchesAmount(refund, trade)) {
            return AMOUNT_MISMATCH;
        }
        if (unapproved) {
            return STATE_MISMATCH;
        }
        if (refund.status() == RefundOrder.State.SUCCEEDED) {
            return trade.state() == PaymentGateway.RefundState.SUCCEEDED ? MATCHED : STATE_MISMATCH;
        }
        if (trade.state() == PaymentGateway.RefundState.PROCESSING) {
            return refund.status() == RefundOrder.State.PROCESSING ? PROCESSING : STATE_MISMATCH;
        }
        try {
            refundService.confirm(payment, refund, trade);
            return trade.state() == PaymentGateway.RefundState.FAILED ? CHANNEL_FAILED : REPAIRED;
        } catch (BusinessException failure) {
            return APPLY_FAILED;
        }
    }

    private Outcome queryFailure(BusinessException failure) {
        return switch (failure.getCode()) {
            case 400, 409 -> IDENTITY_MISMATCH;
            default -> QUERY_FAILED;
        };
    }
}
