package com.xiaoke.order.idempotency;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 接收数据库查出来的回执字段
 */
@Getter @Setter
public class OrderIdempotencyRow {
    private Integer hashVersion;
    private String requestHash;
    private String orderNo;
    private Integer resultStatus;
    private BigDecimal totalAmount;
}
