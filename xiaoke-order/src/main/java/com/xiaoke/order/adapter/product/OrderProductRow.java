package com.xiaoke.order.adapter.product;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class OrderProductRow {
    private Long id,categoryId;
    private String name,image;
    private BigDecimal price;
    private Integer stock,sales,status,deleted;
}
