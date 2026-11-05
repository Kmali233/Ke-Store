package com.xiaoke.product.controller;
import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.product.dto.SkuInput;
import com.xiaoke.product.service.SkuService;
import com.xiaoke.product.vo.SkuVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/product/{pid}/skus") @RequiredArgsConstructor
public class SkuController {
    private final SkuService service;
    @PostMapping @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<SkuVO> create(@PathVariable Long pid,@Valid @RequestBody SkuInput dto) {return Result.success(service.create(pid,dto));}
    @PutMapping("/{id}") @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<Void> update(@PathVariable Long pid,@PathVariable Long id,@Valid @RequestBody SkuInput dto) {service.update(pid,id,dto);return Result.success();}
    @DeleteMapping("/{id}") @AdminPermission(AdminPermissions.PRODUCT_WRITE)
    public Result<Void> delete(@PathVariable Long pid,@PathVariable Long id) {service.delete(pid,id);return Result.success();}
}
