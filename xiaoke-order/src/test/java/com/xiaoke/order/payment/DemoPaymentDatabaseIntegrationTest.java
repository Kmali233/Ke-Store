package com.xiaoke.order.payment;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.admin.AdminJwtService;
import com.xiaoke.common.auth.admin.AdminPrincipal;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.refund.RefundService;
import org.springframework.boot.test.mock.mockito.SpyBean;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
        "xiaoke.order.close-scan-delay-ms=3600000",
        "xiaoke.admin.enabled=true",
        "xiaoke.admin.secret-base64=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=",
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
    @SpyBean DemoPaymentGateway gateway;
    @Autowired AdminJwtService adminJwt;
    @Autowired RefundService refunds;
    private String admin;
    private String shipper;
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
        db.update("INSERT INTO t_admin_account(id,username,password) VALUES(1,'demo_admin','test'),(2,'demo_shipper','test')");
        db.update("INSERT INTO t_admin_account_role(admin_id,role_id) VALUES(1,1),(2,3)");
        admin = adminJwt.issue(new AdminPrincipal(1, "demo_admin", 0));
        shipper = adminJwt.issue(new AdminPrincipal(2, "demo_shipper", 0));
        db.update("INSERT INTO t_category(id,name) VALUES(1,'category')");
        db.update("INSERT INTO t_product(id,name,category_id,price) VALUES(1,'product',1,12.34)");
        db.update("""
                INSERT INTO t_product_sku(id,product_id,name,spec_json,spec_hash,price,stock,sales)
                VALUES(1,1,'sku','[]',REPEAT('a',64),12.34,9,1)
                """);
        buyer = jwt.issue(1L, "demo_buyer");
        other = jwt.issue(2L, "other_buyer");
    }

    @BeforeEach
    void seed() {
        for (String table : List.of("t_trade_reconciliation", "t_order_refund", "t_order_tracking_event",
                "t_demo_refund_trade", "t_order_item", "t_inventory_ledger", "t_admin_audit")) {
            db.update("DELETE FROM " + table);
        }
        db.update("UPDATE t_product_sku SET stock=9,sales=1 WHERE id=1");
        db.update("DELETE FROM t_demo_payment_trade");
        db.update("DELETE FROM t_payment_order");
        db.update("DELETE FROM t_orders");
        db.update("""
                INSERT INTO t_orders(id,order_no,user_id,total_amount,receiver_name,receiver_phone,receiver_address)
                VALUES(1,?,1,12.34,'receiver','phone','address')
                """, ORDER);
        db.update("""
                INSERT INTO t_order_item(order_id,order_no,product_id,product_name,price,quantity,sku_id,sku_name,sku_spec)
                VALUES(1,?,1,'product',12.34,1,1,'sku','[]')
                """, ORDER);
        db.update("""
                INSERT INTO t_inventory_ledger(sku_id,event_type,business_no,quantity,sales_quantity,before_stock,after_stock)
                VALUES(1,'OPENING','OPENING',10,0,0,10),(1,'ORDER_OCCUPY',?,-1,1,10,9)
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

    @Test
    void physicalFulfillmentRoutesAreRetiredAndLegacyOrdersRemainReadable() throws Exception {
        pay();
        db.update("UPDATE t_orders SET status=4,tracking_no='legacy',finish_time=NOW() WHERE order_no=?", ORDER);
        mvc.perform(get(API + "/tracking").header("Authorization", buyer)).andExpect(status().isNotFound());
        mvc.perform(post(API + "/receive").header("Authorization", buyer)).andExpect(status().isNotFound());
        mvc.perform(post(API + "/ship").header("Authorization", shipper)).andExpect(status().isNotFound());
        mvc.perform(get(adminPath() + "/tracking").header("Authorization", shipper)).andExpect(status().isNotFound());
        mvc.perform(put(adminPath() + "/tracking/1").header("Authorization", shipper)).andExpect(status().isNotFound());
        mvc.perform(get(API).header("Authorization", buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(4))
                .andExpect(jsonPath("$.data.trackingNo").doesNotExist())
                .andExpect(jsonPath("$.data.receiveTime").doesNotExist());
        mvc.perform(post(API + "/refund").header("Authorization", buyer)).andExpect(status().isConflict());
    }

    @Test
    void onlyAuthorizedManagersCanReviewAndRejectionKeepsOrderPaid() throws Exception {
        pay();
        mvc.perform(post(API + "/cancel").header("Authorization", buyer)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已付款订单请申请整单退款"));
        mvc.perform(post(API + "/refund").header("Authorization", other)).andExpect(status().isNotFound());
        requestRefund();
        mvc.perform(post(adminPath() + "/refund/approve").header("Authorization", shipper))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/orders/refunds").param("status", "REQUESTED").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderNo").value(ORDER));
        mvc.perform(post(adminPath() + "/refund/reject").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        mvc.perform(post(adminPath() + "/refund/approve").header("Authorization", admin)).andExpect(status().isConflict());
        assertThat(orderStatus()).isEqualTo(1);
        assertThat(count("t_demo_refund_trade")).isZero();
        assertThat(stock()).isEqualTo(9);
    }


    @Test
    void reconciliationRecoversMissedPaymentAndRefundAndDoesNotRestoreStockTwice() throws Exception {
        start();
        mvc.perform(post(CONFIRM + "query").header("Authorization", buyer)).andExpect(status().isOk());
        assertThat(orderStatus()).isZero();
        check("PAYMENT", "REPAIRED");
        assertPaid();
        requestRefund();
        String refundNo = db.queryForObject("SELECT refund_no FROM t_order_refund", String.class);
        requestRefund();
        assertThat(db.queryForObject("SELECT refund_no FROM t_order_refund", String.class)).isEqualTo(refundNo);

        doReturn(Optional.empty()).doCallRealMethod().when(gateway).queryRefund(any(), any());
        mvc.perform(post(adminPath() + "/refund/approve").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertThat(count("t_demo_refund_trade")).isEqualTo(1);
        assertThat(orderStatus()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);

        db.update("UPDATE t_demo_refund_trade SET amount=0.01");
        check("REFUND", "AMOUNT_MISMATCH");
        assertThat(orderStatus()).isEqualTo(1);
        db.update("UPDATE t_demo_refund_trade SET amount=12.34");
        check("REFUND", "REPAIRED");
        check("REFUND", "MATCHED");
        mvc.perform(post(adminPath() + "/refund/approve").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)).andExpect(status().isOk());
        assertThat(orderStatus()).isEqualTo(5);
        assertThat(stock()).isEqualTo(10);
        assertThat(count("t_demo_refund_trade")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_inventory_ledger WHERE event_type='REFUND_RESTORE'",
                Integer.class)).isEqualTo(1);
        mvc.perform(post("/admin/orders/reconciliation/run").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/admin/orders/reconciliation").header("Authorization", admin).param("differences", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_admin_audit WHERE action='REFUND_RECONCILE'",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void notFoundAndGatewayFailureAreDistinctAndRechecksStayAudited() throws Exception {
        start();
        check("PAYMENT", "NOT_FOUND");
        doThrow(new BusinessException(503, "provider unavailable")).when(gateway).query(any());
        check("PAYMENT", "QUERY_FAILED");
        mvc.perform(get("/admin/orders/reconciliation").header("Authorization", admin).param("differences", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].attempts").value(2));
        assertThat(orderStatus()).isZero();
    }

    private String adminPath() {
        return "/admin/orders/" + ORDER;
    }

    private void pay() throws Exception {
        start();
        mvc.perform(post(CONFIRM + "notify").header("Authorization", buyer)).andExpect(status().isOk());
    }

    private void requestRefund() throws Exception {
        mvc.perform(post(API + "/refund").header("Authorization", buyer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED")).andExpect(jsonPath("$.data.amount").value(12.34));
    }

    private void check(String kind, String outcome) throws Exception {
        mvc.perform(post(adminPath() + "/reconciliation/" + kind).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value(outcome));
    }

    private int stock() {
        return db.queryForObject("SELECT stock FROM t_product_sku WHERE id=1", Integer.class);
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int code(Runnable action) {
        try {
            action.run();
            return 200;
        } catch (BusinessException failure) {
            return failure.getCode();
        }
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.await(); return first.call(); });
            var b = pool.submit(() -> { ready.await(); return second.call(); });
            ready.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
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
