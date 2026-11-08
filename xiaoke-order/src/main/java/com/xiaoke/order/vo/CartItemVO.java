package com.xiaoke.order.vo;

import java.math.BigDecimal;

public record CartItemVO(Long skuId, int quantity, boolean selected, Long productId, String productName,
                         String skuName, String skuSpec, String image, BigDecimal price, Integer stock,
                         Availability availability) {
    public enum Availability { AVAILABLE, OFF_SHELF, OUT_OF_STOCK, INVALID_SKU }
}
