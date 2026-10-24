package com.xiaoke.order.controller;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.auth.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.service.*;
import com.xiaoke.order.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/order") @RequiredArgsConstructor
public class OrderController {
    private final OrderCommandService commands;
    private final OrderQueryService queries;

    // 创建订单
    @PostMapping @LoginRequired
    public Result<OrderCreateVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(commands.create(UserContext.requireUserId(),dto));
    }

    // 分页查询订单
    @GetMapping("/page") @LoginRequired @AllowedQuery({"pageNum","pageSize","status"})
    public Result<IPage<OrderSummaryVO>> page(@Valid OrderQueryDTO query) {
        query.validatePage();
        return Result.success(queries.page(UserContext.requireUserId(),query));
    }

    // 获取订单详情
    @GetMapping("/{orderNo}") @LoginRequired
    public Result<OrderDetailVO> detail(@PathVariable String orderNo) {
        return Result.success(queries.detail(UserContext.requireUserId(),orderNo));
    }

    // 获取订单状态
    @GetMapping("/{orderNo}/status") @LoginRequired
    public Result<OrderStatusVO> status(@PathVariable String orderNo) {
        return Result.success(queries.status(UserContext.requireUserId(),orderNo));
    }

    // 订单支付
    @PostMapping("/{orderNo}/pay") @LoginRequired
    public Result<Void> pay(@PathVariable String orderNo,HttpServletRequest request) {
        requireEmptyBody(request);
        commands.pay(UserContext.requireUserId(),orderNo);
        return Result.success();
    }

    // 订单发货
    @PostMapping("/{orderNo}/ship") @LocalAdminOnly
    public Result<Void> ship(@PathVariable String orderNo,@Valid @RequestBody OrderShipDTO dto) {
        commands.ship(orderNo,dto);
        return Result.success();
    }

    // 订单取消
    @PostMapping("/{orderNo}/cancel") @LoginRequired
    public Result<Void> cancel(@PathVariable String orderNo,HttpServletRequest request) {
        requireEmptyBody(request);
        commands.cancel(UserContext.requireUserId(),orderNo);
        return Result.success();
    }

    // 空请求体
    private void requireEmptyBody(HttpServletRequest request) {
        if (request.getContentLengthLong()>0
                || request.getHeader("Transfer-Encoding")!=null) {
            throw new BusinessException(400,"此接口不接受请求体");
        }
    }
}
