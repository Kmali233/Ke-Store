package com.xiaoke.admin.controller;

import com.xiaoke.admin.dto.AdminLoginDTO;
import com.xiaoke.admin.service.AdminAuthService;
import com.xiaoke.admin.vo.AdminLoginVO;

import com.xiaoke.common.auth.admin.AdminRequired;
import com.xiaoke.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;


/**
 * 登录、退出接口
 */
@RestController
@RequestMapping("/admin/auth")
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class AdminAuthController {
    private final AdminAuthService service;
    public AdminAuthController(AdminAuthService service) { this.service = service; }
    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(service.login(dto));
    }
    @AdminRequired
    @PostMapping("/logout")
    public Result<Void> logout() {
        service.logout();
        return Result.success();
    }
}
