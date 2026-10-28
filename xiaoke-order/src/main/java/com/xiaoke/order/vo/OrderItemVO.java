package com.xiaoke.order.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderItemVO(Long productId,String productName,String productImage,BigDecimal price,Integer quantity,BigDecimal amount,
                          Long skuId,String skuName,String skuSpec) { }
