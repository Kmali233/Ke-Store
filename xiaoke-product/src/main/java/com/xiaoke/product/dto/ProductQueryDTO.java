package com.xiaoke.product.dto;

import lombok.Data;

/**
 * 商品分页查询请求
 */
@Data
public class ProductQueryDTO {

    /** 商品名称（模糊） */
    private String name;

    /** 分类ID */
    private Long categoryId;

    /** 页码，默认1 */
    private Long pageNum = 1L;

    /** 每页条数，默认10 */
    private Long pageSize = 10L;
}
