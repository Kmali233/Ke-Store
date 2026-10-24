package com.xiaoke.product.vo;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter @Setter
public class ProductDetailVO extends ProductVO {
    private List<ProductAttrVO> attrs = List.of();
}
