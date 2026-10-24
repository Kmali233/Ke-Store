package com.xiaoke.product.dto;

import com.xiaoke.product.validation.ResourceLocation;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public abstract class CategoryInput {
    @Size(max = 50) @Pattern(regexp = "(?s).*\\S.*")
    private String name;
    @PositiveOrZero
    private Long parentId;
    @Size(max = 255) @ResourceLocation
    private String icon;
    private Integer sort;
    @Min(0) @Max(1)
    private Integer status;

    public void setName(String value) { name = value == null ? null : value.trim(); }
    public void setIcon(String value) { icon = value == null ? null : value.trim(); }
}
