package com.xiaoke.product.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.xiaoke.common.web.PageQuery;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 商品分页查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductQueryDTO extends PageQuery {

    /** 商品名称（模糊） */
    @Size(max = 128)
    private String name;

    /** 分类ID */
    @Positive
    private Long categoryId;

    public void setName(String value) {
        name = value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
