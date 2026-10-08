package com.xiaoke.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品视图对象（返回给前端）
 */
@Data
public class ProductVO {

    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private Integer stock;
    private String image;
    private String description;
    private Integer status;
    private LocalDateTime createTime;
}
