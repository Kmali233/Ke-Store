package com.xiaoke.user.controller;

import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.Result;
import com.xiaoke.user.dto.AddressCreateDTO;
import com.xiaoke.user.dto.AddressUpdateDTO;
import com.xiaoke.user.service.AddressService;
import com.xiaoke.user.vo.AddressVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/address")
@LoginRequired
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    /** 创建地址 */
    @PostMapping
    public Result<AddressVO> create(@Valid @RequestBody AddressCreateDTO dto) {
        return Result.success(service.create(UserContext.requireUserId(), dto));
    }

    /** 获取地址列表 */
    @GetMapping("/list")
    public Result<List<AddressVO>> list() {
        return Result.success(service.list(UserContext.requireUserId()));
    }

    /** 更新地址 */
    @PutMapping
    public Result<AddressVO> update(@Valid @RequestBody AddressUpdateDTO dto) {
        return Result.success(service.update(UserContext.requireUserId(), dto));
    }

    /** 删除地址 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        service.delete(UserContext.requireUserId(), id);
        return Result.success();
    }

    /** 设置默认地址 */
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable("id") Long id, HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(400, "该接口不接收请求体");
        }
        service.setDefault(UserContext.requireUserId(), id);
        return Result.success();
    }
}
