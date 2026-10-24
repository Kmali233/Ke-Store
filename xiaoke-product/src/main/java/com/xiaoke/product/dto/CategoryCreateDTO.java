package com.xiaoke.product.dto;

import jakarta.validation.constraints.NotBlank;

public class CategoryCreateDTO extends CategoryInput {
    @Override @NotBlank public String getName() { return super.getName(); }
}
