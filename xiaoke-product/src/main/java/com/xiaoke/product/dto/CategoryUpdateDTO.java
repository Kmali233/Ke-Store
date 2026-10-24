package com.xiaoke.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CategoryUpdateDTO extends CategoryInput {
    @NotNull @Positive
    private Long id;
}
