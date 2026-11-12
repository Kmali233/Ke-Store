package com.xiaoke.order.controller;

import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.result.Result;
import com.xiaoke.order.payment.DemoPaymentGateway;
import com.xiaoke.order.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile(DemoPaymentGateway.PROFILE)
@RequestMapping("/order/{orderNo}/payment/demo")
@RequiredArgsConstructor
public class DemoPaymentController {
    private final PaymentService payments;

    @PostMapping("/confirm/{delivery:notify|query}")
    @LoginRequired
    public Result<Void> pay(@PathVariable String orderNo, @PathVariable String delivery, HttpServletRequest request) {
        PaymentController.requireEmptyBody(request);
        payments.payDemo(UserContext.requireUserId(), orderNo, "notify".equals(delivery));
        return Result.success();
    }
}
