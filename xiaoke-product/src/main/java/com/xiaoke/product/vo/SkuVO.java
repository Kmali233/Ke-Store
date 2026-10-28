package com.xiaoke.product.vo;
import java.math.BigDecimal;
import java.util.Map;
public record SkuVO(Long id,Long productId,String name,Map<String,String> specs,BigDecimal price,
                    Integer stock,Integer orderCount,String image,Integer status,boolean defaultSku) { }
