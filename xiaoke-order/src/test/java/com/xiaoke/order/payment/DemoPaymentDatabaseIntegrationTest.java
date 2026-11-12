package com.xiaoke.order.payment;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.order.OrderApplication;
import com.xiaoke.order.mapper.PaymentOrderMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "XIAOKE_DEMO_DB_TESTS", matches = "true")
@SpringBootTest(classes = OrderApplication.class, properties = {
        "spring.datasource.username=root", "spring.datasource.password=",
        "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "payment-demo"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoPaymentDatabaseIntegrationTest {
    private static final String ORDER = "20261109000001";
    private static final String API = "/order/" + ORDER;
    private static final String PAYMENT = API + "/payment";
    private static final String CONFIRM = PAYMENT + "/demo/confirm/";

    @Autowired DataSource source;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired PaymentOrderMapper paymentOrders;
    @Autowired DemoPaymentGateway gateway;
    private String buyer;
    private String other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("XIAOKE_DEMO_TEST_DB_URL"));
    }

    @BeforeAll
    void schema() throws Exception {
        try (var connection = source.getConnection()) {
            assertThat(connection.getCatalog()).matches("xiaoke_demo_verify_[a-z0-9_]+");
        }
        new ResourceDatabasePopulator(new ClassPathResource("order-schema.sql")).execute(source);
        db.update("INSERT INTO t_user(id,username,password) VALUES(1,'demo_buyer','test'),(2,'other_buyer','test')");
        buyer = jwt.issue(1L, "demo_buyer");
        other = jwt.issue(2L, "other_buyer");
    }

    @BeforeEach
    void seed() {
        db.update("DELETE FROM t_demo_payment_trade");
        db.update("DELETE FROM t_payment_order");
        db.update("DELETE FROM t_orders");
        db.update("""
                INSERT INTO t_orders(id,order_no,user_id,total_amount,receiver_name,receiver_phone,receiver_address)
                VALUES(1,?,1,12.34,'receiver','phone','address')
                """, ORDER);
    }

    @Test
    void ownerCanPayThroughVerifiedNotificationAndRepeatWithoutChangingReceipt() throws Exception {
        mvc.perform(get(PAYMENT).header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.demoEnabled").value(true))
                .andExpect(jsonPath("$.data.sandboxEnabled").value(false))
                .andExpect(jsonPath("$.data.channel").value("DEMO"));
        start();
        var payment = paymentOrders.findByOrderId(1L);
        start();
        assertThat(paymentOrders.findByOrderId(1L)).isEqualTo(payment);
        assertThat(payment.totalAmount()).isEqualByComparingTo("12.34");

        var modified = gateway.signedNotification(payment);
        modified.put("total_amount", "0.01");
        assertThatThrownBy(() -> gateway.verifyNotification(modified))
                .isInstanceOf(com.xiaoke.common.exception.BusinessException.class);
        assertThat(orderStatus()).isZero();
        mvc.perform(post(API + "/cancel").header("Authorization", buyer)).andExpect(status().isConflict());

        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)).andExpect(status().isOk());
        assertPaid();
        var first = db.queryForMap("SELECT trade_no,pay_time FROM t_payment_order WHERE order_id=1");
        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)).andExpect(status().isOk());
        assertThat(db.queryForMap("SELECT trade_no,pay_time FROM t_payment_order WHERE order_id=1")).isEqualTo(first);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_demo_payment_trade", Integer.class)).isEqualTo(1);
    }

    @Test
    void queryOnlyConfirmsAfterDemoProviderRecordsPayment() throws Exception {
        start();
        mvc.perform(post(PAYMENT + "/query").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING"));
        mvc.perform(post(CONFIRM + "query").header("Authorization", buyer)).andExpect(status().isOk());
        assertThat(orderStatus()).isZero();
        var savedPayment = paymentOrders.findByOrderId(1L);
        assertThat(new DemoPaymentGateway(db).query(savedPayment)).isPresent();
        mvc.perform(post(PAYMENT + "/query").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        assertPaid();
    }

    @Test
    void rejectsOtherBuyersAndChannelMixing() throws Exception {
        mvc.perform(post(CONFIRM + "notify")).andExpect(status().isUnauthorized());
        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)).andExpect(status().isConflict());
        mvc.perform(post(PAYMENT + "/alipay").header("Authorization", buyer)).andExpect(status().isConflict());
        mvc.perform(post(API + "/pay").header("Authorization", buyer)).andExpect(status().isForbidden());
        start();
        mvc.perform(post(CONFIRM + "notify").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(post(PAYMENT + "/demo").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)
                .contentType("application/json").content("{\"amount\":0.01}")).andExpect(status().isBadRequest());
        var request = post("/payment/alipay/notify").contentType("application/x-www-form-urlencoded");
        gateway.signedNotification(paymentOrders.findByOrderId(1L)).forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(content().string("failure"));
        assertThat(orderStatus()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_demo_payment_trade", Integer.class)).isZero();
    }

    private void start() throws Exception {
        mvc.perform(post(PAYMENT + "/demo").header("Authorization", buyer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentUrl")
                        .value("/orders/" + ORDER + "/demo-checkout"));
    }

    private int orderStatus() {
        return db.queryForObject("SELECT status FROM t_orders WHERE id=1", Integer.class);
    }

    private void assertPaid() {
        assertThat(orderStatus()).isEqualTo(1);
        assertThat(paymentOrders.findByOrderId(1L).status()).isEqualTo(PaymentOrder.State.SUCCEEDED);
    }
}
