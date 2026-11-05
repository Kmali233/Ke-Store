package com.xiaoke.product.controller;

import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import com.xiaoke.common.result.Result;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.service.CategoryService;
import com.xiaoke.product.vo.CategoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    @GetMapping("/list")
    public Result<List<CategoryVO>> list() { return Result.success(categoryService.listVisibleCategories()); }

    @PostMapping @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<Void> add(@Valid @RequestBody CategoryCreateDTO dto) {
        categoryService.addCategory(dto);
        return Result.success();
    }

    @PutMapping @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<Void> update(@Valid @RequestBody CategoryUpdateDTO dto) {
        categoryService.updateCategory(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}") @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return Result.success();
    }
}
