package com.xiaoke.order.payment;

import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.mapper.OrdersMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Only the isolated test profile can execute the historical mock endpoint. */
@Component
@Profile("payment-test & !alipay-sandbox & !payment-demo & !prod")
@RequiredArgsConstructor
public class MockPaymentAdapter {
    private final OrdersMapper orders;

    public boolean pay(Orders order) {
        return orders.casPay(order.getId(), order.getUserId()) == 1;
    }
}
