package com.xiaoke.order.payment;

import com.xiaoke.order.entity.Orders;
import com.xiaoke.order.mapper.OrdersMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local simulation and the isolated regression suite share the pending-to-paid transition. */
@Component
@Profile("!alipay-sandbox & !payment-demo & !prod & (payment-test | (dev & payment-local))")
public class MockPaymentAdapter {
    private final OrdersMapper orders;
    private final boolean regressionMode;

    public MockPaymentAdapter(OrdersMapper orders, Environment environment) {
        this.orders = orders;
        regressionMode = environment.acceptsProfiles(Profiles.of("payment-test"));
        if (!regressionMode && !"127.0.0.1".equals(environment.getProperty("server.address"))) {
            throw new IllegalStateException("payment-local requires server.address=127.0.0.1");
        }
    }

    public boolean isLocalSimulation() {
        return !regressionMode;
    }

    public boolean allowsLegacyPaidCancellation() {
        return regressionMode;
    }

    public boolean pay(Orders order) {
        return orders.casPay(order.getId(), order.getUserId()) == 1;
    }
}
