package com.xiaoke.order.dto;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class OrderItemCreateDTO {
    @NotNull @Positive private Long productId;
    @NotNull @Min(1) @Max(999) private Integer quantity;
}
