package com.xiaoke.order.controller;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.payment.AlipaySandboxGateway;
import com.xiaoke.order.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;

/** Provider-signed form and plain text acknowledgement, outside the buyer JSON input policy. */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AlipayNotifyController {
    private final PaymentService payments;

    @PostMapping(value = AlipaySandboxGateway.NOTIFY_PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String receive(HttpServletRequest request) {
        try {
            if (request.getQueryString() != null || request.getContentType() == null
                    || !MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(request.getContentType()))) {
                return "failure";
            }
            var parameters = new HashMap<String, String>();
            for (var entry : request.getParameterMap().entrySet()) {
                if (entry.getValue().length != 1) {
                    return "failure";
                }
                parameters.put(entry.getKey(), entry.getValue()[0]);
            }
            payments.notifyAlipay(parameters);
            return "success";
        } catch (BusinessException ex) {
            if (ex.getCode() >= 500) {
                log.warn("Alipay notification could not be applied, code={}", ex.getCode());
            }
            return "failure";
        } catch (RuntimeException ex) {
            log.warn("Alipay notification failed, type={}", ex.getClass().getSimpleName());
            return "failure";
        }
    }
}
