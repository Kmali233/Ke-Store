package com.xiaoke.order.refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundOrder(long orderId, String refundNo, BigDecimal amount, State status,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    public enum State { REQUESTED, PROCESSING, REJECTED, SUCCEEDED, FAILED }
}
