package com.xiaoke.product.controller;
import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/product/{pid}/skus/{sid}/inventory") @RequiredArgsConstructor
public class InventoryController {
    private final InventoryService service;
    @PostMapping("/adjustments") @AdminPermission(AdminPermissions.INVENTORY_ADJUST)
    public Result<InventoryService.Adjustment> adjust(@PathVariable Long pid,@PathVariable Long sid,@Valid @RequestBody InventoryAdjustmentDTO dto) {
        return Result.success(service.adjust(pid,sid,dto));
    }
    @GetMapping("/ledger") @AdminPermission(AdminPermissions.INVENTORY_ADJUST)
    @AllowedQuery({"type","businessNo","pageNum","pageSize"})
    public Result<Map<String,Object>> page(@PathVariable Long pid,@PathVariable Long sid,@Valid InventoryQuery query) {
        return Result.success(service.page(pid,sid,query));
    }
    @GetMapping("/balance") @AdminPermission(AdminPermissions.INVENTORY_ADJUST) @AllowedQuery({})
    public Result<Map<String,Object>> balance(@PathVariable Long pid,@PathVariable Long sid) {
        return Result.success(service.balance(pid,sid));
    }
}
