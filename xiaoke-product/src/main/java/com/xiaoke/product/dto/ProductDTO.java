package com.xiaoke.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品新增/修改请求
 */
@Data
public class ProductDTO {

    private Long id;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private Long categoryId;

    @NotNull(message = "价格不能为空")
    @PositiveOrZero(message = "价格不能为负")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不能为负")
    private Integer stock;

    private String image;

    private String description;

    private Integer status;
}
