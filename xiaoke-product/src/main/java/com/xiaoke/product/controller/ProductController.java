package com.xiaoke.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.auth.LocalAdminOnly;
import com.xiaoke.common.web.AllowedQuery;
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
    @AllowedQuery({"name", "categoryId", "pageNum", "pageSize"})
    public Result<IPage<ProductVO>> page(@Valid ProductQueryDTO query) {
        query.validatePage();
        return Result.success(productService.pageProducts(query));
    }

    /** 商品详情 */
    @GetMapping("/{id}")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getProductById(id));
    }

    /** 新增商品 */
    @PostMapping
    @LocalAdminOnly   /** 仅允许本地管理员操作:
     这个接口只允许在明确开启的本地开发环境中，由携带正确管理密钥的请求调用 */
    public Result<Void> add(@Valid @RequestBody ProductDTO dto) {
        /**
         * @RequestBody：从 HTTP 请求体中读取内容，按参数类型转换成 ProductDTO。对于这里的 JSON 请求，会使用 Jackson。
         * @Valid：对准备好的 ProductDTO 执行字段校验，例如检查 @NotNull、@PositiveOrZero
         * 流程：
         * 收到 JSON
         *     ↓
         * @RequestBody：让 Spring 读取请求体
         *     ↓
         * Jackson 按配置规则（JacksonInputConfig），把 JSON 转换成 ProductDTO
         *     ↓
         * @Valid：让校验器检查 ProductDTO 的字段规则
         *     ↓
         * 全部通过，才进入 add(...) 方法体
         *     ↓
         * 执行 productService.addProduct(dto)
         *
         * */
        productService.addProduct(dto);
        return Result.success();
    }

    /** 修改商品 */
    @PutMapping
    @LocalAdminOnly
    public Result<Void> update(@Valid @RequestBody ProductDTO dto) {
        productService.updateProduct(dto);
        return Result.success();
    }

    /** 删除商品 */
    @DeleteMapping("/{id}")
    @LocalAdminOnly
    public Result<Void> delete(@PathVariable Long id) {
        productService.removeById(id);
        return Result.success();
    }
}
