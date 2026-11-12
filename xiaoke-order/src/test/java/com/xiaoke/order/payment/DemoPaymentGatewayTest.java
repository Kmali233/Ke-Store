package com.xiaoke.order.payment;

import com.xiaoke.order.controller.DemoPaymentController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoPaymentGatewayTest {
    @Test
    void demoRejectsRegularDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("xiaoke_mall");
        assertThatThrownBy(() -> new DemoPaymentGateway(jdbc))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("xiaoke_demo_");
    }

    @Test
    void productionAndConflictingProfilesDoNotInstallDemo() {
        for (String profiles : new String[]{"prod,payment-demo", "alipay-sandbox,payment-demo", "payment-test,payment-demo"}) {
            new ApplicationContextRunner()
                    .withPropertyValues("spring.profiles.active=" + profiles)
                    .withUserConfiguration(DemoPaymentGateway.class, DemoPaymentController.class,
                            AlipaySandboxGateway.class, MockPaymentAdapter.class)
                    .run(context -> assertThat(context).doesNotHaveBean(DemoPaymentController.class)
                            .doesNotHaveBean(PaymentGateway.class).doesNotHaveBean(MockPaymentAdapter.class));
        }
    }
}
