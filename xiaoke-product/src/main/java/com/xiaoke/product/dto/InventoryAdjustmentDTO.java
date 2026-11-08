package com.xiaoke.product.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class InventoryAdjustmentDTO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}") private String businessNo;
    @NotBlank @Pattern(regexp="INBOUND|ADJUSTMENT") private String type;
    @NotNull private Integer quantity;
    @NotBlank @Size(max=200) private String reason;
}
