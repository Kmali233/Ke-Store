package com.xiaoke.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.result.Result;
import com.xiaoke.product.dto.ProductDTO;
import com.xiaoke.product.dto.ProductQueryDTO;
import com.xiaoke.product.service.ProductService;
import com.xiaoke.product.vo.ProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 商品 Controller
 */
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** 分页查询 */
    @GetMapping("/page")
    public Result<IPage<ProductVO>> page(ProductQueryDTO query) {
        return Result.success(productService.pageProducts(query));
    }

    /** 商品详情 */
    @GetMapping("/{id}")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getProductById(id));
    }

    /** 新增商品 */
    @PostMapping
    public Result<Void> add(@Valid @RequestBody ProductDTO dto) {
        productService.addProduct(dto);
        return Result.success();
    }

    /** 修改商品 */
    @PutMapping
    public Result<Void> update(@Valid @RequestBody ProductDTO dto) {
        productService.updateProduct(dto);
        return Result.success();
    }

    /** 删除商品 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.removeById(id);
        return Result.success();
    }
}
