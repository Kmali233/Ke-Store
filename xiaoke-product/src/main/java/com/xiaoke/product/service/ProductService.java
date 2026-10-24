package com.xiaoke.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.vo.*;

/** 只暴露业务入口，避免 Controller 直接调用通用 CRUD 绕过锁与事务。 */
public interface ProductService {
    IPage<ProductVO> pageProducts(ProductQueryDTO query);
    IPage<ProductVO> searchProducts(ProductSearchDTO query);
    ProductDetailVO getProductById(Long id);
    void addProduct(ProductCreateDTO dto);
    void updateProduct(ProductUpdateDTO dto);
    void deleteProduct(Long id);
}
