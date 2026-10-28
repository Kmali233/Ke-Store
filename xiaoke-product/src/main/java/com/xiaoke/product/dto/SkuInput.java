package com.xiaoke.product.dto;
import com.xiaoke.product.validation.ResourceLocation;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.Map;

@Getter @Setter
public class SkuInput {
    @Size(max=128) @Pattern(regexp="(?s).*\\S.*") private String name;
    private Map<String,String> specs;
    @DecimalMin("0") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) private BigDecimal price;
    @PositiveOrZero private Integer stock;
    @Size(max=255) @ResourceLocation private String image;
    @Min(0) @Max(1) private Integer status;
    public void setName(String value) {name=value==null?null:value.trim();}
    public void setImage(String value) {image=value==null?null:value.trim();}
}
