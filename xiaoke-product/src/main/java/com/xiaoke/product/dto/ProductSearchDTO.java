package com.xiaoke.product.dto;

import com.xiaoke.common.web.PageQuery;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ProductSearchDTO extends PageQuery {
    @NotBlank @Size(max = 128)
    private String keyword;
    public void setKeyword(String value) { keyword = value == null ? null : value.trim(); }
}
