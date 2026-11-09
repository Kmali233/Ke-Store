package com.xiaoke.product.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AdminProductVO(Long id, String name, Long categoryId, BigDecimal price, Long stock,
                             String image, String description, Integer status,
                             @JsonInclude(JsonInclude.Include.NON_NULL) List<Sku> skus) {
    public record Sku(Long id, String name, Map<String, String> specs, BigDecimal price, Integer stock,
                      String image, Integer status, boolean defaultSku) { }
}
