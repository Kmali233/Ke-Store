package com.xiaoke.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.auth.LocalAdminOnly;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.product.dto.ProductCreateDTO;
import com.xiaoke.product.dto.ProductUpdateDTO;
import com.xiaoke.product.dto.ProductSearchDTO;
import com.xiaoke.product.dto.ProductAttrSaveDTO;
import com.xiaoke.product.dto.ProductQueryDTO;
import com.xiaoke.product.service.ProductService;
import com.xiaoke.product.service.ProductAttrService;
import com.xiaoke.product.vo.ProductDetailVO;
import com.xiaoke.product.vo.ProductAttrVO;
import java.util.List;
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
    private final ProductAttrService productAttrService;

    /** 分页查询 */
    @GetMapping("/page")
    @AllowedQuery({"name", "categoryId", "pageNum", "pageSize"})
    public Result<IPage<ProductVO>> page(@Valid ProductQueryDTO query) {
        query.validatePage();
        return Result.success(productService.pageProducts(query));
    }

    /** 商品详情 */
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.getProductById(id));
    }

    /** 新增商品 */
    @PostMapping
    @LocalAdminOnly   /** 仅允许本地管理员操作:
     这个接口只允许在明确开启的本地开发环境中，由携带正确管理密钥的请求调用 */
    public Result<Void> add(@Valid @RequestBody ProductCreateDTO dto) {
        /**
         * @RequestBody：从 HTTP 请求体中读取内容，按参数类型转换成 ProductCreateDTO。对于这里的 JSON 请求，会使用 Jackson。
         * @Valid：对准备好的 ProductCreateDTO 执行字段校验，例如检查 @NotNull、@PositiveOrZero
         * 流程：
         * 收到 JSON
         *     ↓
         * @RequestBody：让 Spring 读取请求体
         *     ↓
         * Jackson 按配置规则（JacksonInputConfig），把 JSON 转换成 ProductCreateDTO
         *     ↓
         * @Valid：让校验器检查 ProductCreateDTO 的字段规则
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
    public Result<Void> update(@Valid @RequestBody ProductUpdateDTO dto) {
        productService.updateProduct(dto);
        return Result.success();
    }

    /** 删除商品 */
    @DeleteMapping("/{id}")
    @LocalAdminOnly
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return Result.success();
    }

    @GetMapping("/search")
    @AllowedQuery({"keyword", "pageNum", "pageSize"})
    public Result<IPage<ProductVO>> search(@Valid ProductSearchDTO query) {
        query.validatePage();
        return Result.success(productService.searchProducts(query));
    }

    @GetMapping("/{id}/attrs")
    public Result<List<ProductAttrVO>> attrs(@PathVariable Long id) {
        return Result.success(productAttrService.listAttrs(id));
    }

    @PostMapping("/{id}/attrs")
    @LocalAdminOnly
    public Result<Void> replaceAttrs(@PathVariable Long id, @RequestBody List<ProductAttrSaveDTO> attrs) {
        // 集合的每个元素（包括 null）由 Service 在事务前统一验证。
        productAttrService.replaceAttrs(id, attrs);
        return Result.success();
    }
}
