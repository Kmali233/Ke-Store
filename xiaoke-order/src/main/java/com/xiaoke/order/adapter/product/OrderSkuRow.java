package com.xiaoke.order.adapter.product;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class OrderSkuRow {
    private Long id,productId;
    private String name,specJson,image;
    private BigDecimal price;
    private Integer stock,sales,status,deleted;
}
