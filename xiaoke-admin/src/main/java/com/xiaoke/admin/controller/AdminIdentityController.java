package com.xiaoke.admin.controller;

import com.xiaoke.common.auth.admin.AdminContext;
import com.xiaoke.common.auth.admin.AdminRequired;
import com.xiaoke.admin.vo.AdminIdentityVO;

import com.xiaoke.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 当前身份接口. */
@RestController
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
@RequestMapping("/admin/auth")
@AdminRequired
public class AdminIdentityController {
    @GetMapping("/me")
    public Result<AdminIdentityVO> me() {
        var principal = AdminContext.requirePrincipal();
        return Result.success(new AdminIdentityVO(principal.adminId(), principal.username()));
    }
}
