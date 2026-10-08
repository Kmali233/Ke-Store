package com.xiaoke.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaoke.product.dto.ProductDTO;
import com.xiaoke.product.dto.ProductQueryDTO;
import com.xiaoke.product.entity.Product;
import com.xiaoke.product.vo.ProductVO;

/**
 * 商品 Service
 */
public interface ProductService extends IService<Product> {

    /** 分页查询商品 */
    IPage<ProductVO> pageProducts(ProductQueryDTO query);

    /** 商品详情 */
    ProductVO getProductById(Long id);

    /** 新增商品 */
    void addProduct(ProductDTO dto);

    /** 修改商品 */
    void updateProduct(ProductDTO dto);
}
