package com.xiaoke.order.dto;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class OrderItemCreateDTO {
    @Positive private Long productId;
    @Positive private Long skuId;
    @NotNull @Min(1) @Max(999) private Integer quantity;
}
