package com.xiaoke.product.dto;

import com.xiaoke.product.validation.ResourceLocation;
import com.xiaoke.product.validation.Utf8Text;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** 可编辑字段。null 在 PUT 中表示保留原值，库存只用来核对。 */
@Getter
@Setter
public abstract class ProductInput {
    @Size(max = 128) @Pattern(regexp = "(?s).*\\S.*")
    private String name;
    @Positive
    private Long categoryId;
    @DecimalMin("0") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2)
    private BigDecimal price;
    @PositiveOrZero
    private Integer stock;
    @Size(max = 255) @ResourceLocation
    private String image;
    @Size(max = 65535) @Utf8Text
    private String description;
    @Min(0) @Max(1)
    private Integer status;

    public void setName(String value) { name = value == null ? null : value.trim(); }
    public void setImage(String value) { image = value == null ? null : value.trim(); }
}
