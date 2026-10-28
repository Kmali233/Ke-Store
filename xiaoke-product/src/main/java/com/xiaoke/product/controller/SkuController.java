package com.xiaoke.product.controller;
import com.xiaoke.common.auth.LocalAdminOnly;
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
    @PostMapping @LocalAdminOnly
    public Result<SkuVO> create(@PathVariable Long pid,@Valid @RequestBody SkuInput dto) {return Result.success(service.create(pid,dto));}
    @PutMapping("/{id}") @LocalAdminOnly
    public Result<Void> update(@PathVariable Long pid,@PathVariable Long id,@Valid @RequestBody SkuInput dto) {service.update(pid,id,dto);return Result.success();}
    @DeleteMapping("/{id}") @LocalAdminOnly
    public Result<Void> delete(@PathVariable Long pid,@PathVariable Long id) {service.delete(pid,id);return Result.success();}
}
