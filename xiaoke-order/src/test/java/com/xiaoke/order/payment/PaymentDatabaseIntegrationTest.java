package com.xiaoke.order.payment;

import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.auth.JwtService;
import com.xiaoke.order.OrderApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfEnvironmentVariable(named = "XIAOKE_PAYMENT_DB_TESTS", matches = "true")
@SpringBootTest(classes = OrderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.username=root", "spring.datasource.password=",
                "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "xiaoke.payment.alipay.app-id=2026110900000001",
                "xiaoke.payment.alipay.seller-id=2088000000000001",
                "xiaoke.payment.alipay.notify-url=https://notify.example.test/api/payment/alipay/notify"
        })
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "alipay-sandbox"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentDatabaseIntegrationTest {
    private static final String ORDER = "20261109000001";
    private static final String API = "/order/" + ORDER;
    private static final String PAYMENT = API + "/payment";
    private static final String TRADE = "202611092200000000000001";
    private static final String APP = "2026110900000001";
    private static final String SELLER = "2088000000000001";
    private static final KeyPair APPLICATION_KEYS = keys();
    private static final KeyPair PROVIDER_KEYS = keys();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @SpyBean AlipaySandboxGateway gateway;
    @LocalServerPort int port;
    String buyerToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("XIAOKE_PAYMENT_TEST_DB_URL"));
        registry.add("xiaoke.payment.alipay.private-key", () -> b64(APPLICATION_KEYS.getPrivate().getEncoded()));
        registry.add("xiaoke.payment.alipay.public-key", () -> b64(PROVIDER_KEYS.getPublic().getEncoded()));
        registry.add("xiaoke.payment.alipay.gateway", () -> "https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        registry.add("xiaoke.payment.alipay.buyer-origin", () -> "http://127.0.0.1:5173");
    }

    @BeforeAll
    void schema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).matches("xiaoke_payment_verify_[a-z0-9_]+");
        }
        new ResourceDatabasePopulator(new ClassPathResource("order-schema.sql")).execute(dataSource);
        db.update("INSERT INTO t_user(id,username,password) VALUES(1,'payment_buyer','test'),(2,'other_buyer','test')");
        db.update("INSERT INTO t_category(id,name) VALUES(1,'category')");
        db.update("INSERT INTO t_product(id,name,category_id,price) VALUES(1,'product',1,99.00)");
        db.update("""
                INSERT INTO t_product_sku(id,product_id,name,spec_json,spec_hash,price,stock,sales)
                VALUES(1,1,'sku','[]',REPEAT('a',64),99.00,9,1)
                """);
        buyerToken = jwt.issue(1L, "payment_buyer");
    }

    @BeforeEach
    void order() {
        db.update("DELETE FROM t_inventory_ledger");
        db.update("DELETE FROM t_payment_order");
        db.update("DELETE FROM t_order_item");
        db.update("DELETE FROM t_orders");
        db.update("UPDATE t_product_sku SET stock=9,sales=1 WHERE id=1");
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
    void checkoutUsesSnapshotAndExistingPaymentNumberAndRequiresOwnership() throws Exception {
        assertThat(mvc.perform(get(PAYMENT)).andReturn().getResponse().getStatus()).isEqualTo(401);
        String other = jwt.issue(2L, "other_buyer");
        for (var request : new MockHttpServletRequestBuilder[]{get(PAYMENT), post(PAYMENT + "/alipay"),
                post(PAYMENT + "/query")}) {
            assertThat(mvc.perform(request.header("Authorization", other)).andReturn().getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(status(post(API + "/pay"))).isEqualTo(403);
        assertThat(status(post(PAYMENT + "/wechat"))).isEqualTo(409);
        var first = checkout();
        var second = checkout();
        assertThat(first).isEqualTo(second);
        assertThat(first.path("out_trade_no").asText()).isEqualTo(paymentNo());
        assertThat(first.path("total_amount").asText()).isEqualTo("12.34");
        assertThat(first.path("product_code").asText()).isEqualTo("FAST_INSTANT_TRADE_PAY");
        assertThat(orderState()).isZero();
        assertThat(status(post(API + "/cancel"))).isEqualTo(409);
        assertStock(9, 1);
    }

    @Test
    void signedNotificationRejectsTamperingWrongAmountAppSellerAndPaymentNumber() throws Exception {
        checkout();
        var params = notification("TRADE_SUCCESS", TRADE);
        var badSignature = sign(params);
        badSignature.put("trade_no", "9999");
        assertThat(notify(badSignature)).isEqualTo("failure");
        for (var mismatch : Map.of("app_id", "999", "seller_id", "999", "total_amount", "12.35",
                "out_trade_no", "a".repeat(32), "trade_status", "UNKNOWN").entrySet()) {
            var invalid = new HashMap<>(params);
            invalid.put(mismatch.getKey(), mismatch.getValue());
            assertThat(notify(sign(invalid))).isEqualTo("failure");
        }
        assertThat(notify(form(sign(params)) + "&out_trade_no=" + paymentNo())).isEqualTo("failure");
        assertThat(orderState()).isZero();
        assertThat(paymentState()).isEqualTo("PENDING");
    }

    @Test
    void notificationAndQueryCanRaceAndLateResultsDoNotChangeTheReceipt() throws Exception {
        checkout();
        var success = sign(notification("TRADE_SUCCESS", TRADE));
        var querying = new CountDownLatch(1);
        var notified = new CountDownLatch(1);
        var result = trade();
        doAnswer(call -> {
            querying.countDown();
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(result);
        }).when(gateway).query(any());
        race(() -> {
            assertThat(querying.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(notify(success)).isEqualTo("success");
            } finally {
                notified.countDown();
            }
            return null;
        }, () -> {
            assertThat(status(post(PAYMENT + "/query"))).isEqualTo(200);
            return null;
        });
        assertPaid();
        var paidAt = db.queryForMap("SELECT o.pay_time,p.pay_time AS receipt_time FROM t_orders o JOIN t_payment_order p ON p.order_id=o.id");
        assertThat(notify(success)).isEqualTo("success");
        assertThat(notify(sign(notification("WAIT_BUYER_PAY", TRADE)))).isEqualTo("success");
        assertThat(notify(sign(notification("TRADE_FINISHED", "9999")))).isEqualTo("failure");
        assertThat(db.queryForMap("SELECT o.pay_time,p.pay_time AS receipt_time FROM t_orders o JOIN t_payment_order p ON p.order_id=o.id"))
                .isEqualTo(paidAt);
        assertThat(status(post(API + "/cancel"))).isEqualTo(409);
        assertThat(status(post(PAYMENT + "/alipay"))).isEqualTo(409);
        assertStock(9, 1);
    }

    @Test
    void queryRecoversAStoredPaymentWithoutNotification() throws Exception {
        db.update("""
                INSERT INTO t_payment_order(order_id,payment_no,channel,app_id,seller_id,total_amount)
                VALUES(1,?, 'ALIPAY_SANDBOX',?,?,12.34)
                """, "9".repeat(32), APP, SELLER);
        doReturn(Optional.of(trade())).when(gateway).query(any());
        assertThat(status(post(PAYMENT + "/query"))).isEqualTo(200);
        assertPaid();
    }

    @Test
    void reusedProviderTradeNumberRollsBackOrderAndPaymentTogether() throws Exception {
        checkout();
        db.update("""
                INSERT INTO t_orders(id,order_no,user_id,total_amount,receiver_name,receiver_phone,receiver_address,status,pay_time)
                VALUES(2,'20261109000002',1,12.34,'receiver','phone','address',1,CURRENT_TIMESTAMP)
                """);
        db.update("""
                INSERT INTO t_payment_order(order_id,payment_no,channel,app_id,seller_id,total_amount,status,trade_no,pay_time)
                VALUES(2,?, 'ALIPAY_SANDBOX',?,?,12.34,'SUCCEEDED',?,CURRENT_TIMESTAMP)
                """, "b".repeat(32), APP, SELLER, TRADE);
        var fields = Map.of("app_id", APP, "seller_id", SELLER, "trade_status", "TRADE_SUCCESS",
                "trade_no", TRADE, "total_amount", "12.34", "out_trade_no", paymentNo());
        assertThat(notify(sign(fields))).isEqualTo("failure");
        assertThat(orderState()).isZero();
        assertThat(paymentState()).isEqualTo("PENDING");
        assertStock(9, 1);
    }

    @Test
    void cancellationAndCheckoutShareOneDecisionAndInventoryFollowsTheWinner() throws Exception {
        var result = race(() -> status(post(PAYMENT + "/alipay")), () -> status(post(API + "/cancel")));
        if (result.get(0) == 200) {
            assertThat(result.get(1)).isEqualTo(409);
            assertThat(paymentState()).isEqualTo("PENDING");
            assertThat(orderState()).isZero();
            assertStock(9, 1);
        } else {
            assertThat(result).containsExactly(409, 200);
            assertThat(orderState()).isEqualTo(5);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM t_payment_order", Integer.class)).isZero();
            assertStock(10, 0);
        }
    }

    @Test
    void orderWithoutPaymentCanStillBeCancelled() throws Exception {
        assertThat(status(post(API + "/cancel"))).isEqualTo(200);
        assertThat(status(post(PAYMENT + "/alipay"))).isEqualTo(409);
        assertThat(orderState()).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM t_payment_order", Integer.class)).isZero();
        assertStock(10, 0);
    }

    private com.fasterxml.jackson.databind.JsonNode checkout() throws Exception {
        var response = mvc.perform(post(PAYMENT + "/alipay").header("Authorization", buyerToken)).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        var uri = URI.create(json.readTree(response.getContentAsString()).path("data").path("paymentUrl").asText());
        assertThat(uri.getHost()).isEqualTo("openapi-sandbox.dl.alipaydev.com");
        var params = new HashMap<String, String>();
        for (String field : uri.getRawQuery().split("&")) {
            var pair = field.split("=", 2);
            params.put(pair[0], URLDecoder.decode(pair[1], UTF_8));
        }
        assertThat(params.get("method")).isEqualTo("alipay.trade.page.pay");
        assertThat(params.get("return_url")).isEqualTo("http://127.0.0.1:5173/orders/" + ORDER + "?paymentReturn=1");
        assertThat(AlipaySignature.rsaCheckV2(new HashMap<>(params), b64(APPLICATION_KEYS.getPublic().getEncoded()),
                "UTF-8", "RSA2")).isTrue();
        return json.readTree(params.get("biz_content"));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", buyerToken)).andReturn().getResponse().getStatus();
    }

    private String notify(Map<String, String> parameters) throws Exception {
        return notify(form(parameters));
    }

    private String notify(String form) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/payment/alipay/notify"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
        return response.body();
    }

    private Map<String, String> notification(String state, String tradeNo) {
        return Map.of("app_id", APP, "seller_id", SELLER, "trade_status", state,
                "trade_no", tradeNo, "total_amount", "12.34", "out_trade_no", paymentNo(), "subject", "小Ke商城沙箱订单");
    }

    private PaymentGateway.Trade trade() {
        return new PaymentGateway.Trade(gateway.account(), paymentNo(), TRADE, "12.34", PaymentGateway.TradeState.TRADE_SUCCESS);
    }

    private String paymentNo() {
        return db.queryForObject("SELECT payment_no FROM t_payment_order WHERE order_id=1", String.class);
    }

    private String paymentState() {
        return db.queryForObject("SELECT status FROM t_payment_order WHERE order_id=1", String.class);
    }

    private int orderState() {
        return db.queryForObject("SELECT status FROM t_orders WHERE id=1", Integer.class);
    }

    private void assertPaid() {
        assertThat(orderState()).isEqualTo(1);
        assertThat(paymentState()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT trade_no FROM t_payment_order", String.class)).isEqualTo(TRADE);
    }

    private void assertStock(int stock, int sales) {
        assertThat(db.queryForObject("SELECT stock FROM t_product_sku WHERE id=1", Integer.class)).isEqualTo(stock);
        assertThat(db.queryForObject("SELECT sales FROM t_product_sku WHERE id=1", Integer.class)).isEqualTo(sales);
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { gate.await(); return first.call(); });
            var b = pool.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return java.util.Arrays.asList(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, String> sign(Map<String, String> parameters) throws Exception {
        var signed = new HashMap<>(parameters);
        String content = AlipaySignature.getSignCheckContentV1(new HashMap<>(parameters));
        signed.put("sign", AlipaySignature.rsaSign(content, b64(PROVIDER_KEYS.getPrivate().getEncoded()), "UTF-8", "RSA2"));
        signed.put("sign_type", "RSA2");
        return signed;
    }

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String b64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }
}
