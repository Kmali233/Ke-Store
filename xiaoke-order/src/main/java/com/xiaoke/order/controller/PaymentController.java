package com.xiaoke.order.controller;

import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.Result;
import com.xiaoke.order.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order/{orderNo}/payment")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;

    @PostMapping("/{channel:alipay|demo|wechat}")
    @LoginRequired
    public Result<PaymentService.Checkout> start(@PathVariable String orderNo, @PathVariable String channel,
                                                 HttpServletRequest request) {
        requireEmptyBody(request);
        return Result.success(payments.start(UserContext.requireUserId(), orderNo, channel));
    }

    @GetMapping
    @LoginRequired
    public Result<PaymentService.Status> status(@PathVariable String orderNo) {
        return Result.success(payments.status(UserContext.requireUserId(), orderNo));
    }

    @PostMapping("/query")
    @LoginRequired
    public Result<PaymentService.Status> query(@PathVariable String orderNo, HttpServletRequest request) {
        requireEmptyBody(request);
        return Result.success(payments.query(UserContext.requireUserId(), orderNo));
    }

    static void requireEmptyBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new BusinessException(400, "此接口不接受请求体");
        }
    }
}
