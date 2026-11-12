package com.xiaoke.order.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipaySandboxGatewayTest {
    @Test
    void rejectsProductionAndLookalikeGatewaysBeforeParsingKeys() {
        for (String url : new String[]{
                "https://openapi.alipay.com/gateway.do",
                "https://openapi-sandbox.dl.alipaydev.com.evil.example/gateway.do",
                "http://openapi-sandbox.dl.alipaydev.com/gateway.do"}) {
            assertThatThrownBy(() -> new AlipaySandboxGateway(url, "1", "2", "", "", "", ""))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS 沙箱");
        }
    }

    @Test
    void rejectsLocalAndPlainHttpNotificationEndpoints() {
        for (String url : new String[]{
                "https://localhost/api/payment/alipay/notify",
                "http://notify.example.test/api/payment/alipay/notify",
                "https://notify.example.test/api/order"}) {
            assertThatThrownBy(() -> new AlipaySandboxGateway("https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                    "1", "2", "", "", url, "http://127.0.0.1:5173"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("通知地址");
        }
    }
}
