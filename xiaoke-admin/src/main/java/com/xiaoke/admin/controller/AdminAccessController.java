package com.xiaoke.admin.controller;

import com.xiaoke.admin.dto.*;
import com.xiaoke.admin.service.AdminAccessService;
import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 提供查询当前权限、角色目录、账号列表、创建账号、分配角色、启停账号六个入口
 */
@RestController
@RequestMapping("/admin/access")
public class AdminAccessController {
    private final AdminAccessService service;
    public AdminAccessController(AdminAccessService service) { this.service=service; }
    @GetMapping("/me") @AdminRequired
    public Result<AdminAccessService.Access> me() { return Result.success(service.me()); }
    @GetMapping("/roles") @AdminPermission(AdminPermissions.MANAGE)
    public Result<List<AdminAccessService.Role>> roles() { return Result.success(service.roles()); }
    @GetMapping("/accounts") @AdminPermission(AdminPermissions.MANAGE)
    public Result<List<AdminAccessService.Account>> accounts() { return Result.success(service.list()); }
    @PostMapping("/accounts") @AdminPermission(AdminPermissions.MANAGE)
    public Result<AdminAccessService.Account> create(@Valid @RequestBody AdminLoginDTO dto) { return Result.success(service.create(dto)); }
    @PutMapping("/accounts/{id}/roles") @AdminPermission(AdminPermissions.MANAGE)
    public Result<Void> roles(@PathVariable long id,@Valid @RequestBody AdminRolesDTO dto) {
        service.assign(id,dto.roleIds()); return Result.success();
    }
    @PutMapping("/accounts/{id}/status") @AdminPermission(AdminPermissions.MANAGE)
    public Result<Void> status(@PathVariable long id,@Valid @RequestBody AdminStatusDTO dto) {
        service.status(id,dto.status()); return Result.success();
    }
}
