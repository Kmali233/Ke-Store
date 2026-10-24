package com.xiaoke.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 新增要求名称、价格、初始库存齐全；没有 id 等服务端字段。 */
public class ProductCreateDTO extends ProductInput {
    @Override @NotBlank public String getName() { return super.getName(); }
    @Override @NotNull public BigDecimal getPrice() { return super.getPrice(); }
    @Override @NotNull public Integer getStock() { return super.getStock(); }
}
