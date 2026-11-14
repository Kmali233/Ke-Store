package com.xiaoke.order.vo;

import java.math.BigDecimal;
import java.util.List;

public record AdminOrderDetailVO(String orderNo, Integer status, String statusDesc, BigDecimal totalAmount,
                                 String remark, List<Item> items) {
    public record Item(String productName, String skuName, Integer quantity, BigDecimal amount) { }
}
