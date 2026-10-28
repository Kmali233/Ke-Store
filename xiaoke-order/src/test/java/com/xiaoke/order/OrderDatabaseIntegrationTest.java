package com.xiaoke.order;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.xiaoke.common.auth.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.product.*;
import com.xiaoke.order.allocator.OrderNoAllocator;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.mapper.*;
import com.xiaoke.order.service.*;
import com.xiaoke.order.idempotency.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.*;
import javax.sql.DataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 仅允许独立临时数据库，实际SQL、行锁、回滚与HTTP预读。 */
@EnabledIfEnvironmentVariable(named="XIAOKE_ORDER_DB_TESTS",matches="true")
@SpringBootTest(classes=OrderApplication.class,properties={
    "spring.datasource.username=root","spring.datasource.password=","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "xiaoke.admin.enabled=true",
        "xiaoke.admin.secret-base64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=3",
    "spring.datasource.hikari.maximum-pool-size=12","xiaoke.local-admin-enabled=true","xiaoke.local-admin-key=order-test-admin",
    "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@AutoConfigureMockMvc @ActiveProfiles("dev") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderDatabaseIntegrationTest {
    static final HttpServer HTTP;
    static final ExecutorService HTTP_POOL=Executors.newCachedThreadPool();
    static final AtomicInteger HTTP_CALLS=new AtomicInteger();
    static volatile Runnable afterPreread=()->{};
    static {
        try {
            HTTP=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            HTTP.setExecutor(HTTP_POOL);
            HTTP.createContext("/api/product/",exchange->{
                HTTP_CALLS.incrementAndGet();
                var callback=afterPreread; afterPreread=()->{}; callback.run();
                String id=exchange.getRequestURI().getPath().substring("/api/product/".length());
                byte[] body=("{\"code\":200,\"data\":{\"id\":"+id+",\"price\":99999999}}").getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            HTTP.start();
        } catch(Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("XIAOKE_ORDER_TEST_DB_URL"));
        registry.add("xiaoke.order.product-base-url",()->"http://127.0.0.1:"+HTTP.getAddress().getPort()+"/api");
    }
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired OrderCommandService commands;
    @Autowired OrderQueryService queries;
    @Autowired OrderTransactionService writes;
    @Autowired LocalAdminProperties admin;
    @Autowired ConfigurableEnvironment environment;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @SpyBean OrderProductMapper productMapper;
    @SpyBean OrderSkuMapper skuMapper;
    @SpyBean OrderItemMapper itemMapper;
    @SpyBean OrdersMapper ordersMapper;
    @SpyBean OrderNoAllocator allocator;
    @SpyBean OrderIdempotencyMapper receiptMapper;
    @SpyBean OrderTransactions transactions;
    JdbcTemplate jdbc;
    long a,b;
    String tokenA,tokenB;

    @BeforeAll void schema() throws Exception {
        try(var c=source.getConnection()) {
            if(!c.getCatalog().matches("xiaoke_order_verify_[a-z0-9_]+")) throw new IllegalStateException("Refusing non-verification database");
        }
        new ResourceDatabasePopulator(new ClassPathResource("order-schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void reset() {
        for(String table:List.of("t_product_sku","t_order_idempotency","t_order_item","t_orders","t_product_attr","t_product","t_category","t_address","t_user")) jdbc.update("DELETE FROM "+table);
        a=insert("INSERT INTO t_user(username,password) VALUES ('buyer_one','test-only-unused')");
        b=insert("INSERT INTO t_user(username,password) VALUES ('buyer_two','test-only-unused')");
        tokenA=jwt.issue(a,"buyer_one"); tokenB=jwt.issue(b,"buyer_two");
        HTTP_CALLS.set(0); afterPreread=()->{};
        environment.setActiveProfiles("dev"); admin.setLocalAdminEnabled(true); admin.setLocalAdminKey("order-test-admin");
    }
    @AfterEach void contextClean() {
        assertThat(MDC.get("traceId")).isNull();
        assertThatThrownBy(UserContext::requireUserId).isInstanceOf(BusinessException.class);
    }
    @AfterAll void close() { HTTP.stop(0); HTTP_POOL.shutdownNow(); }

    @Test void sevenEndpointsPreserveSnapshotsAndInventory() throws Exception {
        long p=product("phone",10,"3.25",null);
        String no=createApi(p,2);
        assertCounts(p,8,2);
        var detail=api("GET","/order/"+no,null,tokenA,200);
        assertThat(detail.get("totalAmount").decimalValue()).isEqualByComparingTo("6.50");
        assertThat(detail.has("userId")).isFalse(); assertThat(detail.has("id")).isFalse();
        assertThat(detail.get("items").get(0).size()).isEqualTo(9);
        assertThat(api("GET","/order/page",null,tokenA,200).get("total").asInt()).isEqualTo(1);
        var status=api("GET","/order/"+no+"/status",null,tokenA,200);
        assertThat(status.size()).isEqualTo(6); assertThat(status.get("items").get(0).size()).isEqualTo(2);
        assertThat(status.has("receiverAddress")).isFalse();
        api("POST","/order/"+no+"/pay",null,tokenA,200);
        assertCounts(p,8,2);
        assertThat(queries.detail(a,no).payTime()).isNotNull();
        api("POST","/order/"+no+"/ship","{\"logisticsCompany\":\" test \",\"trackingNo\":\" T1 \"}",null,200);
        assertThat(queries.status(a,no).status()).isEqualTo(3);
        assertCounts(p,8,2);
        String canceled=createApi(p,1);
        api("POST","/order/"+canceled+"/cancel",null,tokenA,200);
        assertThat(queries.status(a,canceled).status()).isEqualTo(5);
        assertCounts(p,8,2);
    }

    @Test void authenticationRunsBeforeMalformedInputAndChecksAllSixBuyerRoutes() throws Exception {
        for(String route:buyerRoutes()) {
            var parts=route.split(" ",2);
            for(String token:Arrays.asList(null,"Bearer "+tokenA,"bad")) api(parts[0],parts[1],"{broken",token,401);
        }
        assertThat(HTTP_CALLS).hasValue(0);
        for(int state:List.of(0,1,2)) {
            jdbc.update("UPDATE t_user SET status=?,deleted=? WHERE id=?",state==0?0:1,state==1?1:0,a);
            if(state==2) jdbc.update("DELETE FROM t_user WHERE id=?",a);
            for(String route:buyerRoutes()) {
                var parts=route.split(" ",2);
                String body=parts[1].equals("/order")?payload(dto(999L,1)):null;
                api(parts[0],parts[1],body,tokenA,state==0?403:401);
            }
        }
        assertThat(HTTP_CALLS).hasValue(0);
    }

    @Test void shippingGateRejectsBeforeBodyAndAllowsAdminWithoutBuyerToken() throws Exception {
        for(int mode=0;mode<5;mode++) {
            environment.setActiveProfiles(mode==1?new String[]{"prod"}:mode==2?new String[]{"dev","prod"}:new String[]{"dev"});
            admin.setLocalAdminEnabled(mode!=0); admin.setLocalAdminKey(mode==3?"":"order-test-admin");
            var req=post("/order/20261024000001/ship").contentType(MediaType.APPLICATION_JSON).content("{broken");
            if(mode==4) req.with(r->{r.setRemoteAddr("::1");return r;});
            response(req,403);
        }
        environment.setActiveProfiles("dev"); admin.setLocalAdminEnabled(true); admin.setLocalAdminKey("order-test-admin");
        response(post("/order/20261024000001/ship").contentType(MediaType.APPLICATION_JSON).content("{broken"),401);
        response(post("/order/20261024000001/ship").header("X-Local-Admin-Key","wrong").contentType(MediaType.APPLICATION_JSON).content("{broken"),401);
    }

    @Test void malformedRequestsCannotWriteOrCallProductHttp() throws Exception {
        for(String body:List.of("{}","null","{\"items\":[]}","{\"items\":[null],\"addressId\":1}",
                "{\"items\":[{\"productId\":1,\"quantity\":\"1\"}],\"addressId\":1}",
                "{\"items\":[{\"productId\":1,\"quantity\":1}],\"addressId\":1,\"userId\":1}",
                "{\"items\":[{\"productId\":1,\"quantity\":1}],\"addressId\":null}",
                "{\"items\":[{\"productId\":1,\"quantity\":1}],\"addressId\":1,\"receiverName\":null}")) {
            api("POST","/order",body,tokenA,400);
        }
        api("GET","/order/20260230000001",null,tokenA,400);
        for(String q:List.of("status=6","status=","pageNum=0","pageSize=101","userId=1","pageNum=9223372036854775807&pageSize=100","status=1&status=2"))
            api("GET","/order/page?"+q,null,tokenA,400);
        api("GET","/order/page","{}",tokenA,400);
        api("POST","/order/20261024000001/pay","{}",tokenA,400);
        api("POST","/order/20261024000001/cancel","{}",tokenA,400);
        api("POST","/order/20261024000001/pay?x=1",null,tokenA,400);
        assertThat(HTTP_CALLS).hasValue(0); assertThat(count("t_orders")).isZero();
    }

    @Test void addressOwnershipLengthAndImmutableShippingSnapshot() throws Exception {
        long p=product("p",10,"1.00",null);
        long address=insert("INSERT INTO t_address(user_id,receiver_name,receiver_phone,province,city,detail_address) VALUES (?,?,?,?,?,?)",a,"A","1","省","市","路");
        var request=dto(p,1); request.setAddressId(address);
        // 重新构造引用模式，直接字段即使null也不能混入。
        request=json.readValue("{\"items\":[{\"productId\":"+p+",\"quantity\":1}],\"addressId\":"+address+"}",OrderCreateDTO.class);
        String no=commands.create(a,request).orderNo();
        jdbc.update("UPDATE t_address SET detail_address='新地址',deleted=1 WHERE id=?",address);
        assertThat(queries.detail(a,no).receiverAddress()).isEqualTo("省 市 路");
        assertCode(404,()->commands.create(a,reference(p,address)));
        long other=insert("INSERT INTO t_address(user_id,receiver_name,receiver_phone,detail_address) VALUES (?,?,?,?)",b,"B","1","路");
        assertCode(404,()->commands.create(a,reference(p,other)));
        long longAddress=insert("INSERT INTO t_address(user_id,receiver_name,receiver_phone,province,detail_address) VALUES (?,?,?,?,?)",a,"A","1","x","x".repeat(254));
        assertCode(400,()->commands.create(a,reference(p,longAddress)));
        jdbc.update("UPDATE t_address SET detail_address=? WHERE id=?","x".repeat(253),longAddress);
        assertThat(commands.create(a,reference(p,longAddress)).orderNo()).hasSize(14);
    }

    @Test void userIsRecheckedAfterHttpAndSnapshotsUseLockedProductValues() {
        long p=product("old",10,"1.00",null);
        afterPreread=()->{jdbc.update("UPDATE t_product SET name='new',image='/new.png' WHERE id=?",p);jdbc.update("UPDATE t_product_sku SET price=3.25 WHERE product_id=?",p);};
        String no=commands.create(a,dto(p,2)).orderNo();
        assertThat(queries.detail(a,no).items().get(0).productName()).isEqualTo("new");
        assertThat(queries.detail(a,no).totalAmount()).isEqualByComparingTo("6.50");
        jdbc.update("UPDATE t_product SET name='later' WHERE id=?",p);jdbc.update("UPDATE t_product_sku SET price=99 WHERE product_id=?",p);
        assertThat(queries.detail(a,no).items().get(0).price()).isEqualByComparingTo("3.25");
        afterPreread=()->jdbc.update("UPDATE t_user SET status=0 WHERE id=?",a);
        assertCode(403,()->commands.create(a,dto(p,1)));
        assertCounts(p,8,2); assertThat(count("t_orders")).isEqualTo(1);
    }

    @Test void twentyItemsAndQuantityBoundariesAndDuplicateValidation() throws Exception {
        var dto=dto(product("p0",2000,"1",null),999);
        var inputs=new ArrayList<>(dto.getItems());
        for(int i=1;i<20;i++) {
            var item=new OrderItemCreateDTO(); item.setProductId(product("p"+i,2,"1",null)); item.setQuantity(1); inputs.add(item);
        }
        dto.setItems(inputs); commands.create(a,dto);
        assertThat(HTTP_CALLS).hasValue(20); assertThat(count("t_order_item")).isEqualTo(20);
        inputs.add(inputs.get(0)); dto.setItems(inputs); assertCode(400,()->commands.create(a,dto));
        assertThat(HTTP_CALLS).hasValue(20);
    }

    @Test void unavailableProductsAndAncestorChainsLeaveNoOrders() {
        long root=category(0),child=category(root),p=product("p",10,"1",child);
        for(String change:List.of("UPDATE t_category SET status=0 WHERE id="+root,
                "UPDATE t_category SET parent_id="+child+" WHERE id="+root,
                "UPDATE t_category SET parent_id=999999 WHERE id="+root,
                "UPDATE t_product SET status=0 WHERE id="+p,"UPDATE t_product SET deleted=1 WHERE id="+p)) {
            jdbc.update("UPDATE t_category SET status=1,parent_id=0 WHERE id=?",root);
            jdbc.update("UPDATE t_product SET status=1,deleted=0 WHERE id=?",p);
            jdbc.update(change); assertCode(404,()->commands.create(a,dto(p,1))); assertCounts(p,10,0);
        }
        assertThat(count("t_orders")).isZero();
        jdbc.update("UPDATE t_product SET status=1,deleted=0,category_id=NULL WHERE id=?",p);
        commands.create(a,dto(p,1));
    }

    @Test void amountAndCounterBoundariesAreAtomic() {
        long p=product("p",10,"99999999.99",null);
        String no=commands.create(a,dto(p,1)).orderNo();
        assertThat(queries.detail(a,no).totalAmount()).isEqualByComparingTo("99999999.99");
        assertCode(400,()->commands.create(a,dto(p,2)));
        jdbc.update("UPDATE t_product_sku SET price=1,stock=0 WHERE product_id=? AND is_default=1",p); assertCode(409,()->commands.create(a,dto(p,1)));
        jdbc.update("UPDATE t_product_sku SET stock=10,sales=2147483646 WHERE product_id=? AND is_default=1",p); commands.create(a,dto(p,1));
        assertCode(409,()->commands.create(a,dto(p,1)));
        jdbc.update("UPDATE t_product_sku SET sales=-1 WHERE product_id=? AND is_default=1",p); assertCode(500,()->commands.create(a,dto(p,1)));
        assertThat(count("t_orders")).isEqualTo(2);
    }

    @Test void laterDeductionAndMainOrBatchFailuresRollBackEverything() throws Exception {
        long p=product("p",10,"1",null),q=product("q",10,"1",null);
        var dto=dto(p,1); var second=new OrderItemCreateDTO();second.setProductId(q);second.setQuantity(1);
        dto.setItems(List.of(dto.getItems().get(0),second));
        doReturn(0).when(skuMapper).deduct(eq(defaultSku(q)),anyInt());
        assertCode(409,()->commands.create(a,dto)); assertCounts(p,10,0); assertThat(count("t_orders")).isZero();
        org.mockito.Mockito.reset(skuMapper);
        doReturn(0).when(ordersMapper).insert(any(com.xiaoke.order.entity.Orders.class));
        assertCode(500,()->commands.create(a,dto)); assertCounts(p,10,0); assertCounts(q,10,0);
        org.mockito.Mockito.reset(ordersMapper);
        jdbc.execute("CREATE TRIGGER fail_item BEFORE INSERT ON t_order_item FOR EACH ROW BEGIN IF NEW.product_id="+q+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");
        try {
            api("POST","/order",payload(dto),tokenA,500);
            assertCounts(p,10,0); assertCounts(q,10,0); assertThat(count("t_orders")).isZero(); assertThat(count("t_order_item")).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_item"); }
    }

    @Test void onlyOwnerCanReadPayOrCancelAndPaginationIsStable() throws Exception {
        long p=product("p",10,"1",null);
        String first=commands.create(a,dto(p,1)).orderNo(),second=commands.create(a,dto(p,1)).orderNo();
        commands.create(b,dto(p,1));
        jdbc.update("UPDATE t_orders SET create_time='2026-01-01 00:00:00'");
        var page=api("GET","/order/page?pageSize=1",null,tokenA,200);
        assertThat(page.get("total").asInt()).isEqualTo(2); assertThat(page.get("records").get(0).get("orderNo").asText()).isEqualTo(second);
        assertThat(api("GET","/order/page?pageNum=99",null,tokenA,200).get("records").isEmpty()).isTrue();
        for(String suffix:List.of("","/status","/pay","/cancel")) api(suffix.equals("/pay")||suffix.equals("/cancel")?"POST":"GET","/order/"+first+suffix,null,tokenB,404);
        commands.pay(a,first);
        assertThat(api("GET","/order/page?status=1",null,tokenA,200).get("total").asInt()).isEqualTo(1);
    }

    @Test void payAndCancelAreSingleTransitionsAndShippingCannotOverrideThem() {
        long p=product("p",10,"1",null);
        String no=commands.create(a,dto(p,2)).orderNo();
        assertCode(409,()->commands.ship(no,ship()));
        commands.pay(a,no); assertCode(409,()->commands.pay(a,no)); assertCounts(p,8,2);
        commands.cancel(a,no); assertCode(409,()->commands.cancel(a,no)); assertCode(409,()->commands.ship(no,ship()));
        assertThat(queries.detail(a,no).payTime()).isNotNull(); assertCounts(p,10,0);
    }

    @Test void reservedStatesTwoAndFourDoNotAddNewTransitionEndpoints() {
        long p=product("p",10,"1",null);
        String no=commands.create(a,dto(p,1)).orderNo();
        jdbc.update("UPDATE t_orders SET status=2 WHERE order_no=?",no); commands.ship(no,ship());
        assertCode(409,()->commands.ship(no,ship()));
        assertThat(queries.status(a,no).status()).isEqualTo(3); assertCode(409,()->commands.cancel(a,no));
        jdbc.update("UPDATE t_orders SET status=4 WHERE order_no=?",no);
        assertCode(409,()->commands.pay(a,no)); assertCode(409,()->commands.ship(no,ship())); assertCode(409,()->commands.cancel(a,no));
    }

    @Test void cancellationRestoresSoftDeletedAndDisabledProductsWithoutCategoryLocks() {
        long category=category(0),p=product("p",10,"1",category);
        String no=commands.create(a,dto(p,2)).orderNo();
        jdbc.update("UPDATE t_product SET deleted=1,status=0 WHERE id=?",p);
        jdbc.update("UPDATE t_category SET status=0 WHERE id=?",category);
        commands.cancel(a,no); assertCounts(p,10,0);
        assertThat(jdbc.queryForObject("SELECT deleted FROM t_product WHERE id=?",Integer.class,p)).isEqualTo(1);
    }

    @Test void corruptOrMissingDetailsNeverPartiallyRestoreInventory() {
        long p=product("p",100,"1",null);
        for(String damage:List.of("DELETE FROM t_order_item WHERE order_no=?",
                "UPDATE t_order_item SET deleted=1 WHERE order_no=?",
                "UPDATE t_order_item SET quantity=0 WHERE order_no=?",
                "UPDATE t_order_item SET product_id=999999 WHERE order_no=?",
                "UPDATE t_order_item SET order_no='broken' WHERE order_no=?")) {
            String no=commands.create(a,dto(p,1)).orderNo();
            int stock=jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE product_id=? AND is_default=1",Integer.class,p);
            jdbc.update(damage,no); assertCode(500,()->commands.cancel(a,no));
            assertThat(jdbc.queryForObject("SELECT status FROM t_orders WHERE order_no=?",Integer.class,no)).isZero();
            assertThat(jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE product_id=? AND is_default=1",Integer.class,p)).isEqualTo(stock);
        }
    }

    @Test void restoreOverflowAndFailureAfterCasRollBackOrderStatus() {
        long p=product("p",10,"1",null);
        String no=commands.create(a,dto(p,1)).orderNo();
        jdbc.update("UPDATE t_product_sku SET stock=2147483647 WHERE product_id=? AND is_default=1",p);
        assertCode(409,()->commands.cancel(a,no));
        jdbc.update("UPDATE t_product_sku SET stock=9,sales=0 WHERE product_id=? AND is_default=1",p);
        assertCode(409,()->commands.cancel(a,no));
        jdbc.update("UPDATE t_product_sku SET sales=1 WHERE product_id=? AND is_default=1",p);
        doReturn(0).when(skuMapper).restore(eq(defaultSku(p)),anyInt());
        assertCode(409,()->commands.cancel(a,no)); assertThat(queries.status(a,no).status()).isZero(); assertCounts(p,9,1);
    }

    @Test void orderNumbersIncludeSoftDeletedMaximumAndDetectExhaustionAndBadHistory() {
        long p=product("p",10,"1",null);
        String first=commands.create(a,dto(p,1)).orderNo();
        jdbc.update("UPDATE t_orders SET deleted=1 WHERE order_no=?",first);
        String next=commands.create(a,dto(p,1)).orderNo();
        assertThat(Long.parseLong(next)).isEqualTo(Long.parseLong(first)+1);
        String prefix=next.substring(0,8);
        jdbc.update("UPDATE t_orders SET order_no=? WHERE order_no=?",prefix+"999999",next);
        assertCode(409,()->commands.create(a,dto(p,1))); assertCounts(p,8,2);
        jdbc.update("UPDATE t_orders SET order_no=? WHERE order_no=?",prefix+"bad",prefix+"999999");
        assertCode(500,()->commands.create(a,dto(p,1)));
    }

    @Test void twoTransactionsCompetingForLastStockCannotOversell() throws Exception {
        long p=product("p",1,"1",null);
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        try {
            var first=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return code(()->writes.createOnce(a,dto(p,1),day));});
            var second=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return code(()->writes.createOnce(b,dto(p,1),day));});
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertCounts(p,0,1); assertThat(count("t_orders")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentPayAndConcurrentCancelOnlyApplyOnce() throws Exception {
        long p=product("p",10,"1",null);String no=commands.create(a,dto(p,1)).orderNo();
        assertThat(race(()->commands.pay(a,no),()->commands.pay(a,no))).containsExactlyInAnyOrder(200,409);
        assertThat(race(()->commands.cancel(a,no),()->commands.cancel(a,no))).containsExactlyInAnyOrder(200,409);
        assertCounts(p,10,0);
    }

    @Test void shippingAndCancelHaveOnlyOneWinner() throws Exception {
        long p=product("p",10,"1",null);String no=commands.create(a,dto(p,1)).orderNo();commands.pay(a,no);
        assertThat(race(()->commands.ship(no,ship()),()->commands.cancel(a,no))).containsExactlyInAnyOrder(200,409);
        int status=queries.status(a,no).status();
        assertThat(status).isIn(3,5);assertCounts(p,status==5?10:9,status==5?0:1);
    }

    @Test void payRacingCancelLeavesCanceledOrderAndRestoresOnce() throws Exception {
        long p=product("p",10,"1",null);String no=commands.create(a,dto(p,1)).orderNo();
        var outcomes=race(()->commands.pay(a,no),()->commands.cancel(a,no));
        assertThat(outcomes.get(1)).isEqualTo(200);assertThat(outcomes.get(0)).isIn(200,409);
        assertThat(queries.status(a,no).status()).isEqualTo(5);assertCounts(p,10,0);
    }

    @Test void lockTimeoutAndChangedCategoryRollBackWithoutDeducting() throws Exception {
        long cat=category(0),p=product("p",10,"1",cat);
        try(var block=source.getConnection()){
            block.setAutoCommit(false);
            try(var st=block.createStatement()){
                st.executeQuery("SELECT id FROM t_product WHERE id="+p+" FOR UPDATE").close();
                assertCode(409,()->commands.create(a,dto(p,1)));
            }finally{block.rollback();}
        }
        var pool=Executors.newSingleThreadExecutor();
        try(var block=source.getConnection()){
            block.setAutoCommit(false);
            try(var st=block.createStatement()){
                st.executeUpdate("UPDATE t_category SET parent_id=999999 WHERE id="+cat);
                var create=pool.submit(()->code(()->commands.create(a,dto(p,1))));
                awaitWait();block.commit();assertThat(create.get(8,TimeUnit.SECONDS)).isEqualTo(409);
            }finally{block.rollback();}
        }finally{pool.shutdownNow();}
        assertCounts(p,10,0);assertThat(count("t_orders")).isZero();
    }

    @Test void actualUniqueIndexCollisionRetriesAWholeFreshTransactionAcrossTwoFacades() throws Exception {
        long p=product("p",10,"1",null),q=product("q",10,"1",null);
        String prefix=LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE);
        var simultaneous=new CyclicBarrier(2);var calls=new AtomicInteger();
        var real=new OrderNoAllocator(ordersMapper);
        doAnswer(invocation->{
            if(calls.incrementAndGet()<=2){simultaneous.await(5,TimeUnit.SECONDS);return prefix+"000001";}
            return real.next(invocation.getArgument(0));
        }).when(allocator).next(any());
        var first=newFacade();var second=newFacade();
        assertThat(race(()->first.create(a,dto(p,1)),()->second.create(b,dto(q,1)))).containsExactly(200,200);
        assertThat(calls).hasValue(3);assertThat(count("t_orders")).isEqualTo(2);assertThat(count("t_order_item")).isEqualTo(2);
        assertCounts(p,9,1);assertCounts(q,9,1);
    }

    @Test void threeRealOrderNumberCollisionsRollBackEveryDeduction() {
        long p=product("p",10,"1",null);String existing=commands.create(a,dto(p,1)).orderNo();
        doReturn(existing).when(allocator).next(any());
        clearInvocations(allocator);
        assertCode(409,()->commands.create(a,dto(p,2)));
        verify(allocator,times(3)).next(any());assertCounts(p,9,1);assertThat(count("t_orders")).isEqualTo(1);
    }

    @Test void reversedProductInputOrderStillLocksAndCommitsConsistently() throws Exception {
        long p=product("p",10,"1",null),q=product("q",10,"1",null);
        var left=dto(p,1);var right=dto(q,1);
        var items=List.of(left.getItems().get(0),right.getItems().get(0));
        left.setItems(items);right.setItems(List.of(items.get(1),items.get(0)));
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(race(()->writes.createOnce(a,left,day),()->writes.createOnce(b,right,day))).containsExactly(200,200);
        assertCounts(p,8,2);assertCounts(q,8,2);
    }

    @Test void detailUsesOneSnapshotWhileShippingCommits() throws Exception {
        long p=product("p",10,"1",null);String no=commands.create(a,dto(p,1)).orderNo();commands.pay(a,no);
        var reading=new CountDownLatch(1);var committed=new CountDownLatch(1);
        doAnswer(invocation->{
            if(Thread.currentThread().getName().equals("order-snapshot-reader")){
                reading.countDown();if(!committed.await(8,TimeUnit.SECONDS))throw new IllegalStateException("writer timeout");
            }
            return sqlSession.selectList("com.xiaoke.order.mapper.OrderItemMapper.selectActiveByOrderId",Map.of("id",invocation.getArgument(0)));
        }).when(itemMapper).selectActiveByOrderId(anyLong());
        var pool=Executors.newSingleThreadExecutor(r->new Thread(r,"order-snapshot-reader"));
        try{
            var detail=pool.submit(()->queries.detail(a,no));assertThat(reading.await(5,TimeUnit.SECONDS)).isTrue();
            commands.ship(no,ship());committed.countDown();
            var old=detail.get(8,TimeUnit.SECONDS);assertThat(old.status()).isEqualTo(1);assertThat(old.logisticsCompany()).isNull();
            assertThat(queries.detail(a,no).status()).isEqualTo(3);
        }finally{committed.countDown();pool.shutdownNow();}
    }

    @Test void productCategoryChangingAfterDiscoveryAbortsOrderWithoutTakingNewCategoryLocks() throws Exception {
        long oldCategory=category(0),newCategory=category(0),pid=product("moving",10,"1",oldCategory);
        var pool=Executors.newSingleThreadExecutor();
        try(var blocker=source.getConnection()){
            blocker.setAutoCommit(false);
            try(var statement=blocker.createStatement()){
                statement.executeUpdate("UPDATE t_product SET category_id="+newCategory+" WHERE id="+pid);
                var create=pool.submit(()->code(()->commands.create(a,dto(pid,1))));
                awaitWait();blocker.commit();
                assertThat(create.get(8,TimeUnit.SECONDS)).isEqualTo(409);
            }finally{blocker.rollback();}
        }finally{pool.shutdownNow();}
        assertCounts(pid,10,0);assertThat(count("t_orders")).isZero();
    }

    @Test void idempotentReplayReturnsOriginalReceiptAfterCancelAndProductDeletion() {
        long p=product("p",10,"3.25",null);
        var first=commands.create(a,dto(p,2),"Replay_1");
        commands.cancel(a,first.orderNo());
        jdbc.update("UPDATE t_product SET deleted=1,status=0,price=88 WHERE id=?",p);
        jdbc.update("UPDATE t_orders SET deleted=1 WHERE order_no=?",first.orderNo());
        HTTP_CALLS.set(0);
        assertThat(commands.create(a,dto(p,2),"Replay_1")).isEqualTo(first);
        assertThat(commands.creationResult(a,"Replay_1")).isEqualTo(first);
        assertThat(first.status()).isZero();assertCounts(p,10,0);
        assertThat(HTTP_CALLS).hasValue(0);assertThat(count("t_order_idempotency")).isEqualTo(1);
    }

    @Test void concurrentSameKeyAcrossIndependentFacadesCreatesOneOrder() throws Exception {
        long p=product("p",10,"1",null);
        var first=newFacade();var second=newFacade();
        assertThat(race(()->first.create(a,dto(p,2),"race"),()->second.create(a,dto(p,2),"race"))).containsExactly(200,200);
        assertCounts(p,8,2);assertThat(count("t_orders")).isEqualTo(1);
        assertThat(count("t_order_item")).isEqualTo(1);assertThat(count("t_order_idempotency")).isEqualTo(1);
    }

    @Test void concurrentSameKeyDifferentQuantitiesHasOneWinner() throws Exception {
        long p=product("p",10,"1",null);
        var first=newFacade();var second=newFacade();
        assertThat(race(()->first.create(a,dto(p,1),"conflict"),()->second.create(a,dto(p,2),"conflict")))
                .containsExactlyInAnyOrder(200,409);
        assertThat(count("t_orders")).isEqualTo(1);assertThat(count("t_order_idempotency")).isEqualTo(1);
        int quantity=jdbc.queryForObject("SELECT quantity FROM t_order_item",Integer.class);
        assertCounts(p,10-quantity,quantity);
    }

    @Test void keysAreCaseSensitiveAndScopedToUserWhileLegacyStillCreatesTwice() {
        long p=product("p",10,"1",null);
        var first=commands.create(a,dto(p,1),"Key");
        assertThat(commands.create(a,dto(p,1),"key").orderNo()).isNotEqualTo(first.orderNo());
        assertThat(commands.create(b,dto(p,1),"Key").orderNo()).isNotEqualTo(first.orderNo());
        assertThat(commands.create(a,dto(p,1)).orderNo()).isNotEqualTo(commands.create(a,dto(p,1)).orderNo());
        assertCounts(p,5,5);assertThat(count("t_order_idempotency")).isEqualTo(3);
    }

    @Test void receiptInsertFailureRollsBackReceiptOrderItemsAndStockThenSameKeyCanRetry() {
        long p=product("p",10,"1",null);
        doAnswer(invocation->{
            sqlSession.insert("com.xiaoke.order.idempotency.OrderIdempotencyMapper.insert",
                Map.of("uid",invocation.getArgument(0),"request",invocation.getArgument(1),"result",invocation.getArgument(2)));
            assertThat(count("t_order_idempotency")).isEqualTo(1);
            throw new BusinessException(500,"injected after receipt write");
        }).when(receiptMapper).insert(anyLong(),any(),any());
        assertCode(500,()->commands.create(a,dto(p,2),"rollback"));
        assertCounts(p,10,0);assertThat(count("t_orders")).isZero();
        assertThat(count("t_order_item")).isZero();assertThat(count("t_order_idempotency")).isZero();
        org.mockito.Mockito.reset(receiptMapper);
        commands.create(a,dto(p,2),"rollback");assertCounts(p,8,2);
        assertThat(count("t_order_idempotency")).isEqualTo(1);
    }

    @Test void simulatedLostCommitAcknowledgementRequiresLookupAndNeverAutomaticallyRecreates() {
        long p=product("p",10,"1",null);
        doAnswer(invocation->{
            invocation.callRealMethod(); // Real DB commit succeeds; simulate losing its acknowledgement.
            throw new org.springframework.transaction.TransactionSystemException("injected unknown completion");
        }).when(transactions).create(any());
        assertThatThrownBy(()->commands.create(a,dto(p,2),"unknown"))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        var saved=commands.creationResult(a,"unknown");
        assertThat(commands.create(a,dto(p,2),"unknown")).isEqualTo(saved);
        verify(transactions,times(1)).create(any());assertCounts(p,8,2);
        assertThat(count("t_orders")).isEqualTo(1);
    }

    @Test void replayAddressReferenceSurvivesAddressDeletionAndNormalizedItemOrdering() {
        long p=product("p",10,"1",null),q=product("q",10,"2",null);
        long address=insert("INSERT INTO t_address(user_id,receiver_name,receiver_phone,detail_address) VALUES (?,?,?,?)",a,"A","1","Road");
        var request=reference(p,address);request.setItems(List.of(dto(p,1).getItems().get(0),dto(q,2).getItems().get(0)));
        request.setRemark(" note ");var first=commands.create(a,request,"address");
        jdbc.update("DELETE FROM t_address WHERE id=?",address);
        request.setItems(List.of(request.getItems().get(1),request.getItems().get(0)));request.setRemark("note");
        assertThat(commands.create(a,request,"address")).isEqualTo(first);
        assertCode(409,()->commands.create(a,dto(p,1),"address"));
    }

    @Test void idempotencyHttpHeaderAndResultOwnershipContract() throws Exception {
        long p=product("p",10,"1",null);String body=payload(dto(p,1));
        for(String key:List.of("","has space","bad,joined","a".repeat(65))) {
            response(post("/order").header("Authorization",tokenA).header("Idempotency-Key",key)
                    .contentType(MediaType.APPLICATION_JSON).content(body),400);
        }
        response(post("/order").header("Authorization",tokenA).header("Idempotency-Key","a","b")
                .contentType(MediaType.APPLICATION_JSON).content(body),400);
        var first=response(post("/order").header("Authorization",tokenA).header("Idempotency-Key","api")
                .contentType(MediaType.APPLICATION_JSON).content(body),200).get("data");
        assertThat(api("GET","/order/idempotency/api",null,tokenA,200)).isEqualTo(first);
        api("GET","/order/idempotency/api",null,tokenB,404);
        api("GET","/order/idempotency/api",null,null,401);
        jdbc.update("UPDATE t_user SET status=0 WHERE id=?",a);
        api("GET","/order/idempotency/api",null,tokenA,403);
    }

    @Test void twoSkusOfOneSpuHaveIndependentPricesStockAndImmutableSnapshots() {
        long pid=product("phone",10,"1",null),black=sku(pid,"black",3,"2.50"),white=sku(pid,"white",4,"4.00");
        var dto=skuDto(black,2);dto.setItems(List.of(dto.getItems().get(0),skuDto(white,1).getItems().get(0)));
        var created=commands.create(a,dto,"two-skus");assertThat(created.totalAmount()).isEqualByComparingTo("9.00");
        assertSku(black,1,2);assertSku(white,3,1);assertCounts(pid,10,0);
        jdbc.update("UPDATE t_product SET name='changed' WHERE id=?",pid);
        jdbc.update("UPDATE t_product_sku SET name='later',spec_json='{\"color\":\"new\"}',price=99,status=0,deleted=1 WHERE id=?",black);
        var detail=queries.detail(a,created.orderNo());assertThat(detail.items()).hasSize(2);
        var original=detail.items().stream().filter(i->i.skuId().equals(black)).findFirst().orElseThrow();
        assertThat(original.productName()).isEqualTo("phone");assertThat(original.skuName()).isEqualTo("black");
        assertThat(original.skuSpec()).isEqualTo("{\"color\":\"black\"}");assertThat(original.price()).isEqualByComparingTo("2.50");
        assertThat(commands.create(a,dto,"two-skus")).isEqualTo(created);
        commands.cancel(a,created.orderNo());assertSku(black,3,0);assertSku(white,4,0);
    }

    @Test void legacyNullSkuSnapshotQueriesAndCancelsIntoDefaultSku() {
        long pid=product("legacy",5,"2",null);var created=commands.create(a,dto(pid,2),"legacy-v1");
        jdbc.update("UPDATE t_order_item SET sku_id=NULL,sku_name=NULL,sku_spec=NULL WHERE order_no=?",created.orderNo());
        assertThat(queries.detail(a,created.orderNo()).items().get(0).skuId()).isNull();
        assertThat(commands.create(a,dto(pid,2),"legacy-v1")).isEqualTo(created);
        commands.cancel(a,created.orderNo());assertCounts(pid,5,0);
    }

    @Test void skuAvailabilityExclusivityAndResolvedDuplicatesAreEnforced() throws Exception {
        long pid=product("p",5,"1",null),sid=sku(pid,"blue",2,"3");
        var request=skuDto(sid,1);request.getItems().get(0).setProductId(pid);
        assertCode(400,()->commands.create(a,request));
        var duplicate=dto(pid,1);duplicate.setItems(List.of(duplicate.getItems().get(0),skuDto(defaultSku(pid),1).getItems().get(0)));
        assertCode(400,()->commands.create(a,duplicate));
        jdbc.update("UPDATE t_product_sku SET status=0 WHERE id=?",sid);assertCode(404,()->commands.create(a,skuDto(sid,1)));
        jdbc.update("UPDATE t_product_sku SET status=1,deleted=1 WHERE id=?",sid);assertCode(404,()->commands.create(a,skuDto(sid,1)));
        assertSku(sid,2,0);assertThat(count("t_orders")).isZero();
    }

    @Test void independentFacadesRaceForOneSkuWithoutTouchingSiblingSku() throws Exception {
        long pid=product("p",5,"1",null),sid=sku(pid,"red",1,"3"),sibling=sku(pid,"blue",4,"4");
        var first=newFacade();var second=newFacade();
        assertThat(race(()->first.create(a,skuDto(sid,1),"one"),()->second.create(b,skuDto(sid,1),"two"))).containsExactlyInAnyOrder(200,409);
        assertSku(sid,0,1);assertSku(sibling,4,0);assertCounts(pid,5,0);
    }

    private OrderCreateDTO skuDto(long sid,int quantity) {var value=dto(1,quantity);value.getItems().get(0).setProductId(null);value.getItems().get(0).setSkuId(sid);return value;}
    private long sku(long pid,String name,int stock,String price) {
        String spec="{\"color\":\""+name+"\"}";
        return insert("INSERT INTO t_product_sku(product_id,name,spec_json,spec_hash,price,stock) VALUES (?,?,?,SHA2(?,256),?,?)",pid,name,spec,spec,new java.math.BigDecimal(price),stock);
    }
    private void assertSku(long sid,int stock,int sales){assertThat(jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE id=?",Integer.class,sid)).isEqualTo(stock);assertThat(jdbc.queryForObject("SELECT sales FROM t_product_sku WHERE id=?",Integer.class,sid)).isEqualTo(sales);}

    private OrderCommandFacade newFacade(){
        return new OrderCommandFacade(context.getBean(OrderInput.class),context.getBean(OrderUsers.class),
            context.getBean(ProductDetailHttpClient.class),writes,context.getBean(OrderTransactions.class),
            context.getBean(Clock.class),context.getBean(com.xiaoke.order.config.OrderProperties.class),context.getBean(OrderIdempotency.class));
    }
    private List<Integer> race(Runnable first,Runnable second) throws Exception {
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return code(first);});
            var b=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return code(second);});
            return List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    private void awaitWait() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0)return;
            Thread.sleep(10);
        }throw new AssertionError("No real database lock wait");
    }
    private List<String> buyerRoutes(){return List.of("POST /order","GET /order/page","GET /order/20261024000001","GET /order/20261024000001/status","POST /order/20261024000001/pay","POST /order/20261024000001/cancel");}
    private String createApi(long pid,int quantity)throws Exception{return api("POST","/order",payload(dto(pid,quantity)),tokenA,200).get("orderNo").asText();}
    private JsonNode api(String method,String path,String body,String token,int expected)throws Exception{
        var req=request(HttpMethod.valueOf(method),path).header("X-Local-Admin-Key","order-test-admin");
        if(token!=null)req.header("Authorization",token);
        if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(body);
        return response(req,expected).get("data");
    }
    private JsonNode response(MockHttpServletRequestBuilder req,int expected)throws Exception{
        var response=mvc.perform(req).andReturn().getResponse();var value=json.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        assertThat(value.path("code").asInt()).isEqualTo(expected);assertThat(response.getHeader("X-Trace-Id")).hasSize(32);
        if(expected!=200)assertThat(value.get("data").isNull()).isTrue();return value;
    }
    private String payload(OrderCreateDTO dto) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode tree=json.valueToTree(dto);
        if(dto.getAddressId()==null) tree.remove("addressId");
        else tree.remove(List.of("receiverName","receiverPhone","receiverAddress"));
        return json.writeValueAsString(tree);
    }
    private OrderCreateDTO dto(long pid,int quantity){
        var item=new OrderItemCreateDTO();item.setProductId(pid);item.setQuantity(quantity);
        var dto=new OrderCreateDTO();dto.setItems(List.of(item));dto.setReceiverName("A");dto.setReceiverPhone("1");dto.setReceiverAddress("Road");return dto;
    }
    private OrderCreateDTO reference(long pid,long aid){var dto=new OrderCreateDTO();dto.setItems(dto(pid,1).getItems());dto.setAddressId(aid);return dto;}
    private OrderShipDTO ship(){var dto=new OrderShipDTO();dto.setLogisticsCompany("test");dto.setTrackingNo("T1");return dto;}
    private long product(String name,int stock,String price,Long category){
        long pid=insert("INSERT INTO t_product(name,price,stock,category_id) VALUES (?,?,?,?)",name,new java.math.BigDecimal(price),stock,category);
        jdbc.update("INSERT INTO t_product_sku(product_id,name,spec_json,spec_hash,price,stock,sales,is_default) SELECT id,'default','{}',SHA2('{}',256),price,stock,sales,1 FROM t_product WHERE id=?",pid);
        return pid;
    }
    private long defaultSku(long pid){return jdbc.queryForObject("SELECT id FROM t_product_sku WHERE product_id=? AND is_default=1",Long.class,pid);}
    private long category(long parent){return insert("INSERT INTO t_category(name,parent_id) VALUES ('test',?)",parent);}
    private long insert(String sql,Object...values){
        var keys=new GeneratedKeyHolder();jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);return ps;},keys);return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void assertCounts(long pid,int stock,int sales){assertThat(jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE product_id=? AND is_default=1",Integer.class,pid)).isEqualTo(stock);assertThat(jdbc.queryForObject("SELECT sales FROM t_product_sku WHERE product_id=? AND is_default=1",Integer.class,pid)).isEqualTo(sales);}
    private int code(Runnable action){try{action.run();return 200;}catch(BusinessException failure){return failure.getCode();}}
    private void assertCode(int code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(code));}
    @Autowired com.xiaoke.common.auth.admin.AdminAuthentication backendAuthentication;
    @Autowired com.xiaoke.common.auth.admin.AdminJwtService backendTokens;

    @Test void readOnlyAdminAdapterUsesSharedRulesAndSeesRevocationImmediately() {
        jdbc.update("DELETE FROM t_admin_account");
        jdbc.update("INSERT INTO t_admin_account(id,username,password) VALUES(99,'ops_admin','unused-test-only')");
        var principal = new com.xiaoke.common.auth.admin.AdminPrincipal(99, "ops_admin", 0);
        String token = backendTokens.issue(principal);
        assertThat(backendAuthentication.authenticate(token)).isEqualTo(principal);
        jdbc.update("UPDATE t_admin_account SET status=0 WHERE id=99");
        assertThatThrownBy(() -> backendAuthentication.authenticate(token)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_admin_account SET status=1,token_version=1 WHERE id=99");
        assertThatThrownBy(() -> backendAuthentication.authenticate(token)).isInstanceOf(BusinessException.class);
        String fresh = backendTokens.issue(new com.xiaoke.common.auth.admin.AdminPrincipal(99, "ops_admin", 1));
        assertThat(backendAuthentication.authenticate(fresh).adminId()).isEqualTo(99);
        jdbc.update("UPDATE t_admin_account SET deleted=1 WHERE id=99");
        assertThatThrownBy(() -> backendAuthentication.authenticate(fresh)).isInstanceOf(BusinessException.class);
        jdbc.update("DELETE FROM t_admin_account");
        assertThatThrownBy(() -> backendAuthentication.authenticate(fresh)).isInstanceOf(BusinessException.class);
    }

    @Test void administratorAccountEndpointsBelongOnlyToAdminApplication() throws Exception {
        for (String path : java.util.List.of("/admin/auth/me", "/admin/auth/login", "/admin/auth/logout")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(404);
        }
    }

}
