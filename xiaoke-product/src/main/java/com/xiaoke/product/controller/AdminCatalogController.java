package com.xiaoke.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.product.dto.ProductQueryDTO;
import com.xiaoke.product.service.CategoryService;
import com.xiaoke.product.service.ProductService;
import com.xiaoke.product.vo.AdminCategoryVO;
import com.xiaoke.product.vo.AdminProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController {
    private final ProductService products;
    private final CategoryService categories;

    @GetMapping("/products/page")
    @AdminPermission({AdminPermissions.PRODUCT_WRITE, AdminPermissions.INVENTORY_ADJUST})
    @AllowedQuery({"name", "categoryId", "pageNum", "pageSize"})
    public Result<IPage<AdminProductVO>> page(@Valid ProductQueryDTO query) {
        return Result.success(products.pageForAdmin(query));
    }

    @GetMapping("/products/{id}")
    @AdminPermission({AdminPermissions.PRODUCT_WRITE, AdminPermissions.INVENTORY_ADJUST})
    @AllowedQuery({})
    public Result<AdminProductVO> detail(@PathVariable Long id) {
        return Result.success(products.detailForAdmin(id));
    }

    @GetMapping("/categories")
    @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    @AllowedQuery({})
    public Result<List<AdminCategoryVO>> categories() {
        return Result.success(categories.listForAdmin());
    }
}
