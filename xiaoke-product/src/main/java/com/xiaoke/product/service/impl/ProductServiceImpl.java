package com.xiaoke.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.product.dto.ProductDTO;
import com.xiaoke.product.dto.ProductQueryDTO;
import com.xiaoke.product.entity.Product;
import com.xiaoke.product.mapper.ProductMapper;
import com.xiaoke.product.service.ProductService;
import com.xiaoke.product.vo.ProductVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 商品 Service 实现
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    @Override
    public IPage<ProductVO> pageProducts(ProductQueryDTO query) {
        Page<Product> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .like(StringUtils.isNotBlank(query.getName()), Product::getName, query.getName())
                .eq(query.getCategoryId() != null, Product::getCategoryId, query.getCategoryId())
                .orderByDesc(Product::getCreateTime);
        return this.page(page, wrapper).convert(this::toVO);
    }

    @Override
    public ProductVO getProductById(Long id) {
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return toVO(product);
    }

    @Override
    public void addProduct(ProductDTO dto) {
        Product product = new Product();
        BeanUtils.copyProperties(dto, product);
        product.setId(null);
        this.save(product);
    }

    @Override
    public void updateProduct(ProductDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("商品id不能为空");
        }
        Product product = new Product();
        BeanUtils.copyProperties(dto, product);
        this.updateById(product);
    }

    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(product, vo);
        return vo;
    }
}
