package com.xiaoke.user.controller;

import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.result.Result;
import com.xiaoke.user.dto.LoginDTO;
import com.xiaoke.user.dto.RegisterDTO;
import com.xiaoke.user.service.UserService;
import com.xiaoke.user.vo.LoginVO;
import com.xiaoke.user.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** 注册 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        service.register(dto);
        return Result.success();
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(service.login(dto));
    }

    /** 获取当前登录用户信息 */
    @LoginRequired
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(service.me(UserContext.requireUserId()));
    }
}
