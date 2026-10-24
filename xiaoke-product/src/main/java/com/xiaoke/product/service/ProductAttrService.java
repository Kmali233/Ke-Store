package com.xiaoke.product.service;

import com.xiaoke.product.dto.ProductAttrSaveDTO;
import com.xiaoke.product.vo.ProductAttrVO;
import java.util.List;

public interface ProductAttrService {
    List<ProductAttrVO> listAttrs(Long productId);
    void replaceAttrs(Long productId, List<ProductAttrSaveDTO> attrs);
}
