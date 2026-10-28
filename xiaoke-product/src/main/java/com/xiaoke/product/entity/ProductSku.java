package com.xiaoke.product.entity;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ProductSku {
    private Long id,productId;
    private String name,specJson,specHash,image;
    private BigDecimal price;
    private Integer stock,sales,status,deleted,isDefault;
}
