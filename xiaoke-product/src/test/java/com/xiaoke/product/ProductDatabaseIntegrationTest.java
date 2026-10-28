package com.xiaoke.product;

import com.fasterxml.jackson.databind.*;
import com.xiaoke.common.auth.LocalAdminProperties;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.mapper.*;
import com.xiaoke.product.service.*;
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
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 写入、并发和故障仅在独立 MySQL 测试库执行；启动时先验证库名。 */
@EnabledIfEnvironmentVariable(named = "XIAOKE_PRODUCT_DB_TESTS", matches = "true")
@SpringBootTest(classes = ProductApplication.class, properties = {
        "spring.datasource.username=root", "spring.datasource.password=",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "xiaoke.admin.enabled=true",
        "xiaoke.admin.secret-base64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=3",
        "spring.datasource.hikari.maximum-pool-size=10",
        "xiaoke.local-admin-enabled=true", "xiaoke.local-admin-key=isolated-test-admin-key"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductDatabaseIntegrationTest {
    static final String KEY = "isolated-test-admin-key";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("XIAOKE_PRODUCT_TEST_DB_URL"));
    }
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductService products;
    @Autowired ProductAttrService attrs;
    @Autowired CategoryService categories;
    @Autowired SkuService skuService;
    @Autowired LocalAdminProperties admin;
    @Autowired ConfigurableEnvironment environment;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @SpyBean ProductMapper productMapper;
    @SpyBean ProductAttrMapper attrMapper;
    JdbcTemplate jdbc;

    @BeforeAll void schema() throws Exception {
        try (var connection = source.getConnection()) {
            if (!connection.getCatalog().matches("xiaoke_product_verify_[a-z0-9_]+")) {
                throw new IllegalStateException("Refusing to modify a non-verification database");
            }
        }
        new ResourceDatabasePopulator(new ClassPathResource("product-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM t_product_sku"); jdbc.update("DELETE FROM t_product_attr"); jdbc.update("DELETE FROM t_product"); jdbc.update("DELETE FROM t_category");
        environment.setActiveProfiles("dev"); admin.setLocalAdminEnabled(true); admin.setLocalAdminKey(KEY);
    }
    @AfterEach void cleanContext() { assertThat(MDC.get("traceId")).isNull(); }

    @Test void allTwelveEndpointsAndWhitelistedViews() throws Exception {
        api("POST", "/category", "{\"name\":\" 手机 \",\"sort\":-1}", 200);
        long category = jdbc.queryForObject("SELECT id FROM t_category WHERE name='手机'", Long.class);
        api("PUT", "/category", "{\"id\":" + category + ",\"icon\":\"/icons/phone.png\"}", 200);
        assertThat(api("GET", "/category/list", null, 200).get(0).size()).isEqualTo(5);
        api("POST", "/product", "{\"name\":\" 手机标准版 \",\"price\":1999.99,\"stock\":8,\"categoryId\":" + category + "}", 200);
        long id = jdbc.queryForObject("SELECT id FROM t_product", Long.class);
        var page = api("GET", "/product/page", null, 200);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("records").get(0).get("orderCount").asInt()).isZero();
        var detail = api("GET", "/product/" + id, null, 200);
        assertThat(detail.size()).isEqualTo(12);
        assertThat(detail.get("attrs").isEmpty()).isTrue();
        assertThat(detail.has("deleted")).isFalse(); assertThat(detail.has("sales")).isFalse();
        assertThat(detail.get("createTime").asText()).contains("T");
        assertThat(api("GET", "/product/search?keyword=手机", null, 200).get("total").asLong()).isEqualTo(1);
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"新版\",\"stock\":8}", 200);
        api("POST", "/product/" + id + "/attrs", "[{\"attrName\":\" 容量 \",\"attrValue\":\" 256GB \",\"attrGroup\":\" \",\"sort\":-2}]", 200);
        var values = api("GET", "/product/" + id + "/attrs", null, 200);
        assertThat(values.get(0).size()).isEqualTo(4);
        assertThat(values.get(0).get("attrName").asText()).isEqualTo("容量");
        assertThat(values.get(0).get("attrGroup").isNull()).isTrue();
        assertThat(api("GET", "/product/" + id, null, 200).get("attrs")).isEqualTo(values);
        api("DELETE", "/product/" + id, null, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_attr WHERE deleted=0", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM t_product_sku WHERE product_id=? AND is_default=1", Integer.class, id)).isEqualTo(8);
        api("DELETE", "/product/" + id, null, 404);
        api("DELETE", "/category/" + category, null, 200); api("DELETE", "/category/" + category, null, 404);
    }

    @Test void sevenWritesEnforceAdminGateBeforeInputParsing() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            environment.setActiveProfiles(mode == 1 ? new String[]{"prod"} : mode == 2 ? new String[]{"dev", "prod"} : new String[]{"dev"});
            admin.setLocalAdminEnabled(mode != 0); admin.setLocalAdminKey(mode == 3 ? "" : KEY);
            for (String route : writeRoutes()) {
                var parts = route.split(" ", 2);
                var req = request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{broken");
                if (mode == 4) { req.with(r -> { r.setRemoteAddr("::1"); return r; }); }
                response(req, 403);
            }
        }
        environment.setActiveProfiles("dev"); admin.setLocalAdminEnabled(true); admin.setLocalAdminKey(KEY);
        for (String route : writeRoutes()) {
            var parts = route.split(" ", 2);
            response(request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{broken"), 401);
            response(request(HttpMethod.valueOf(parts[0]), parts[1]).header("X-Local-Admin-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content("{broken"), 401);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product", Long.class)).isZero();
    }

    @Test void publicVisibilityIncludesAllAncestorsAndStablePagination() throws Exception {
        long root = category("root", 0), child = category("child", root);
        long older = product("visible_old", child), newer = product("visible_new", child), plain = product("plain", null);
        long hidden = product("hidden", child);
        jdbc.update("UPDATE t_product SET status=0 WHERE id=?", hidden);
        jdbc.update("UPDATE t_product SET create_time='2026-01-01 00:00:00'");
        var p1 = api("GET", "/product/page?pageSize=1&pageNum=1", null, 200);
        var p2 = api("GET", "/product/page?pageSize=1&pageNum=2", null, 200);
        assertThat(p1.get("total").asLong()).isEqualTo(3);
        assertThat(p1.get("records").get(0).get("id").asLong()).isEqualTo(plain);
        assertThat(p2.get("records").get(0).get("id").asLong()).isEqualTo(newer);
        var beyond = api("GET", "/product/page?pageNum=99", null, 200);
        assertThat(beyond.get("current").asLong()).isEqualTo(99);
        assertThat(beyond.get("total").asLong()).isEqualTo(3); assertThat(beyond.get("records").isEmpty()).isTrue();
        api("GET", "/product/" + hidden, null, 404);
        api("PUT", "/category", "{\"id\":" + root + ",\"status\":0}", 200);
        assertThat(api("GET", "/category/list", null, 200).isEmpty()).isTrue();
        assertThat(api("GET", "/product/page", null, 200).get("total").asLong()).isEqualTo(1);
        assertThat(api("GET", "/product/page?categoryId=" + child, null, 200).get("total").asLong()).isZero();
        assertThat(api("GET", "/product/search?keyword=visible", null, 200).get("total").asLong()).isZero();
        api("GET", "/product/" + older, null, 404); api("GET", "/product/" + newer + "/attrs", null, 404);
        api("GET", "/product/" + plain, null, 200);
        jdbc.update("UPDATE t_category SET deleted=1 WHERE id=?", root);
        assertThat(api("GET", "/category/list", null, 200).isEmpty()).isTrue();
    }

    @Test void literalSearchEscapesPercentUnderscoreBangAndBackslash() throws Exception {
        for (String term : List.of("%", "_", "!", "\\", "' OR 1=1 --")) { product("literal" + term + "match", null); }
        product("ordinary", null);
        for (String term : List.of("%", "_", "!", "\\", "' OR 1=1 --")) {
            var result = response(get("/product/search").param("keyword", term).header("Authorization", "expired"), 200).get("data");
            assertThat(result.get("total").asLong()).isEqualTo(1);
        }
    }

    @Test void queryAndJsonContractsRejectMalformedInputs() throws Exception {
        for (String query : List.of("pageNum=0", "pageNum=-1", "pageNum=abc", "pageNum=", "pageSize=0", "pageSize=101",
                "pageNum=9223372036854775807&pageSize=100", "x=1", "pageSize=1&pageSize=2", "categoryId=0")) {
            api("GET", "/product/page?" + query, null, 400);
        }
        api("GET", "/product/search", null, 400);
        response(get("/product/search").param("keyword", " "), 400);
        response(get("/product/search").param("keyword", "a".repeat(129)), 400);
        api("GET", "/product/0", null, 400); api("GET", "/product/abc", null, 400);
        api("GET", "/category/list?sort=1", null, 400); api("GET", "/product/page", "{}", 400);
        for (String body : List.of("{\"name\":\"x\",\"price\":1,\"stock\":1,\"orderCount\":99}",
                "{\"name\":\"x\",\"price\":1,\"stock\":1,\"name\":\"y\"}", "{\"name\":\"x\",\"price\":1,\"stock\":1} {}",
                "{\"name\":\"x\",\"price\":1,\"stock\":\"1\"}", "{\"name\":\"x\",\"price\":1,\"stock\":2147483648}")) {
            api("POST", "/product", body, 400);
        }
        api("POST", "/category", "{\"name\":\"x\",\"parentId\":-1}", 400);
        api("POST", "/category", "{\"name\":\"x\",\"status\":true}", 400);
        api("POST", "/category", "{\"name\":\"x\",\"status\":2}", 400);
        response(post("/product").header("X-Local-Admin-Key", KEY).contentType(MediaType.TEXT_PLAIN).content("x"), 415);
        api("PATCH", "/product", "{}", 405); api("GET", "/unknown/resource", null, 404);
        api("POST", "/product", "{\"name\":\"" + "x".repeat(1048577) + "\"}", 413);
    }

    @Test void priceTextAndOtherLengthsAreCheckedBeforeSql() throws Exception {
        api("POST", "/product", "{\"name\":\"zero\",\"price\":0,\"stock\":0}", 200);
        api("POST", "/product", "{\"name\":\"max\",\"price\":99999999.99,\"stock\":2147483647}", 200);
        for (String price : List.of("-1", "1.234", "100000000", "\"1.00\"")) {
            api("POST", "/product", "{\"name\":\"bad\",\"price\":" + price + ",\"stock\":1}", 400);
        }
        api("POST", "/product", "{\"name\":\"" + "x".repeat(129) + "\",\"price\":1,\"stock\":1}", 400);
        long id = product("text", null);
        api("PUT", "/product", json.writeValueAsString(Map.of("id", id, "description", "中".repeat(21845))), 200);
        api("PUT", "/product", json.writeValueAsString(Map.of("id", id, "description", "中".repeat(21846))), 400);
        api("PUT", "/product", json.writeValueAsString(Map.of("id", id, "image", "x".repeat(256))), 400);
        api("POST", "/category", json.writeValueAsString(Map.of("name", "x".repeat(51))), 400);
        api("PUT", "/product", "{\"id\":" + id + ",\"stock\":-1}", 400);
    }

    @Test void partialUpdatesKeepNullsClearStringsAndNeverOverwriteCounters() throws Exception {
        long id = product("original", null);
        jdbc.update("UPDATE t_product SET image='image',description='text' WHERE id=?", id);
        api("PUT", "/product", "{\"id\":" + id + "}", 200);
        api("PUT", "/product", "{\"id\":" + id + ",\"price\":1}", 200);
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":null,\"image\":null,\"description\":null}", 200);
        assertThat(products.getProductById(id).getImage()).isEqualTo("image");
        api("PUT", "/product", "{\"id\":" + id + ",\"image\":\"\",\"description\":\"\",\"stock\":10}", 200);
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"bad\",\"stock\":11}", 409);
        var row = products.getProductById(id);
        assertThat(row.getName()).isEqualTo("original"); assertThat(row.getImage()).isEmpty();
        assertThat(row.getDescription()).isEmpty(); assertThat(row.getStock()).isEqualTo(10);
        assertThat(row.getOrderCount()).isEqualTo(4);
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"   \"}", 400);
        api("PUT", "/product", "{\"id\":" + id + ",\"orderCount\":0}", 400);
        api("PUT", "/product", "{\"id\":9223372036854775807}", 404);
    }

    @Test void categoryCyclesVisibilitySortingAndReferenceDeletion() throws Exception {
        long a = category("a", 0), b = category("b", a), c = category("c", 0);
        api("PUT", "/category", "{\"id\":" + a + ",\"parentId\":" + a + "}", 400);
        api("PUT", "/category", "{\"id\":" + a + ",\"parentId\":" + b + "}", 409);
        api("PUT", "/category", "{\"id\":" + c + ",\"sort\":-1}", 200);
        assertThat(api("GET", "/category/list", null, 200).get(0).get("id").asLong()).isEqualTo(c);
        api("DELETE", "/category/" + a, null, 409);
        long p = product("down", c);
        jdbc.update("UPDATE t_product SET status=0 WHERE id=?", p);
        api("DELETE", "/category/" + c, null, 409);
        jdbc.update("UPDATE t_product SET deleted=1 WHERE id=?", p);
        api("DELETE", "/category/" + c, null, 200);
        api("DELETE", "/category/" + b, null, 200); api("DELETE", "/category/" + a, null, 200);
        api("POST", "/category", "{\"name\":\"missing\",\"parentId\":" + a + "}", 404);
    }

    @Test void disabledOldChainsPermitCleanupButNewReferencesAndReenableNeedActiveChains() throws Exception {
        long a = category("a", 0), b = category("b", a), p = product("p", b), root = category("healthy", 0);
        api("PUT", "/category", "{\"id\":" + a + ",\"status\":0}", 200);
        api("PUT", "/product", "{\"id\":" + p + ",\"name\":\"edited\",\"status\":0}", 200);
        api("PUT", "/product", "{\"id\":" + p + ",\"status\":1}", 409);
        api("POST", "/product", "{\"name\":\"new\",\"price\":0,\"stock\":1,\"categoryId\":" + b + "}", 409);
        api("POST", "/category", "{\"name\":\"new\",\"parentId\":" + b + "}", 409);
        api("PUT", "/category", "{\"id\":" + b + ",\"name\":\"edited\",\"status\":0}", 200);
        api("PUT", "/category", "{\"id\":" + b + ",\"status\":1}", 409);
        api("PUT", "/category", "{\"id\":" + b + ",\"parentId\":" + root + ",\"status\":1}", 200);
        api("PUT", "/product", "{\"id\":" + p + ",\"status\":1}", 200);
        jdbc.update("UPDATE t_product SET category_id=999999 WHERE id=?", p);
        api("PUT", "/product", "{\"id\":" + p + ",\"categoryId\":" + root + "}", 200);
        jdbc.update("UPDATE t_product SET category_id=999999 WHERE id=?", p);
        api("DELETE", "/product/" + p, null, 200);
    }

    @Test void invalidAttributesDoNotClearOldSetAndEmptyArrayDoes() throws Exception {
        long id = product("p", null);
        api("POST", "/product/" + id + "/attrs", "[{\"attrName\":\"old\",\"attrValue\":\"v\"}]", 200);
        for (String body : List.of("null", "{}", "[null]", "[{\"attrName\":\"x\"}]",
                "[{\"attrName\":\"x\",\"attrValue\":\"v\",\"productId\":1}]", "[{\"attrName\":\"x\",\"attrValue\":\"v\",\"sort\":\"1\"}]")) {
            api("POST", "/product/" + id + "/attrs", body, 400);
            assertThat(attrs.listAttrs(id)).hasSize(1);
        }
        api("POST", "/product/" + id + "/attrs", null, 400);
        api("POST", "/product/" + id + "/attrs", "[]", 200);
        assertThat(attrs.listAttrs(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_attr WHERE deleted=1", Long.class)).isEqualTo(1);
    }

    @Test void midReplacementSqlFailureRollsBackNewRowsAndRestoresOldSet() throws Exception {
        long id = product("p", null); attrs.replaceAttrs(id, batch("old"));
        jdbc.execute("""
                CREATE TRIGGER fail_attr BEFORE INSERT ON t_product_attr FOR EACH ROW
                BEGIN IF NEW.attr_value='explode' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END
                """);
        try {
            api("POST", "/product/" + id + "/attrs",
                    "[{\"attrName\":\"first\",\"attrValue\":\"ok\"},{\"attrName\":\"second\",\"attrValue\":\"explode\"}]", 500);
            assertThat(attrs.listAttrs(id).get(0).attrName()).isEqualTo("old");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_attr", Long.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER fail_attr"); }
    }

    @Test void updateReturningZeroAndDeleteFailureAreNotReportedAsSuccess() throws Exception {
        long id = product("p", null); attrs.replaceAttrs(id, batch("old"));
        doReturn(0).when(productMapper).updateEditableFields(any());
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"changed\"}", 500);
        assertThat(products.getProductById(id).getName()).isEqualTo("p");
        doThrow(new IllegalStateException("test failure")).when(attrMapper).softDeleteByProductId(id);
        api("DELETE", "/product/" + id, null, 500);
        assertThat(products.getProductById(id).getAttrs()).hasSize(1);
    }

    @Test void lockTimeoutReturns409AfterRollback() throws Exception {
        long id = product("p", null);
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("SELECT id FROM t_product WHERE id=" + id + " FOR UPDATE").close();
                api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"changed\"}", 409);
            } finally { blocker.rollback(); }
        }
        assertThat(products.getProductById(id).getName()).isEqualTo("p");
    }

    @Test void simultaneousParentMovesCannotCreateCycle() throws Exception {
        long a = category("a", 0), b = category("b", 0);
        var start = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return code(() -> categories.updateCategory(categoryUpdate(a, b))); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return code(() -> categories.updateCategory(categoryUpdate(b, a))); });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(categories.listVisibleCategories()).hasSize(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentReplacementsNeverMergeSets() throws Exception {
        long id = product("p", null);
        var start = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); attrs.replaceAttrs(id, batch("A1", "A2")); return true; });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); attrs.replaceAttrs(id, batch("B1", "B2")); return true; });
            assertThat(first.get(10, TimeUnit.SECONDS) && second.get(10, TimeUnit.SECONDS)).isTrue();
            var names = attrs.listAttrs(id).stream().map(v -> v.attrName()).toList();
            assertThat(names).isIn(List.of(List.of("A1", "A2"), List.of("B1", "B2")));
        } finally { pool.shutdownNow(); }
    }

    @Test void replacementVersusDeletionLeavesNoActiveAttrsOnDeletedProduct() throws Exception {
        long id = product("p", null);
        var start = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var replace = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return code(() -> attrs.replaceAttrs(id, batch("new"))); });
            var delete = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); products.deleteProduct(id); return true; });
            assertThat(delete.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(replace.get(10, TimeUnit.SECONDS)).isIn(200, 404);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_attr WHERE product_id=? AND deleted=0", Long.class, id)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void detailKeepsOneSnapshotWhileAnotherTransactionReplacesAttrs() throws Exception {
        long id = product("p", null); attrs.replaceAttrs(id, batch("old"));
        var reading = new CountDownLatch(1); var committed = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("catalog-snapshot-reader")) {
                reading.countDown();
                if (!committed.await(8, TimeUnit.SECONDS)) { throw new IllegalStateException("writer timed out"); }
            }
            // Mapper 是 MyBatis 接口代理，不能对抽象接口 callRealMethod。
            // 继续执行同一映射语句，仍复用读事务的连接与快照。
            return sqlSession.selectList("com.xiaoke.product.mapper.ProductAttrMapper.selectActiveByProductId", Map.of("id", id));
        }).when(attrMapper).selectActiveByProductId(id);
        var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "catalog-snapshot-reader"));
        try {
            var detail = pool.submit(() -> products.getProductById(id));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            attrs.replaceAttrs(id, batch("new1", "new2")); committed.countDown();
            assertThat(detail.get(8, TimeUnit.SECONDS).getAttrs().get(0).attrName()).isEqualTo("old");
            assertThat(products.getProductById(id).getAttrs()).hasSize(2);
        } finally { committed.countDown(); pool.shutdownNow(); }
    }

    @Test void categoryDeletionRacingNewReferencesNeverLeavesOrphans() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            long target = category("target" + operation, 0), existing = product("existing" + operation, null);
            int mode = operation;
            var start = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
            try {
                var deletion = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return code(() -> categories.deleteCategory(target)); });
                var reference = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return code(() -> {
                        if (mode == 0) {
                            var dto = new ProductCreateDTO(); dto.setName("new"); dto.setPrice(java.math.BigDecimal.ONE); dto.setStock(0); dto.setCategoryId(target);
                            products.addProduct(dto);
                        } else if (mode == 1) {
                            var dto = new ProductUpdateDTO(); dto.setId(existing); dto.setCategoryId(target); products.updateProduct(dto);
                        } else {
                            var dto = new CategoryCreateDTO(); dto.setName("child"); dto.setParentId(target); categories.addCategory(dto);
                        }
                    });
                });
                int deleteCode = deletion.get(10, TimeUnit.SECONDS), refCode = reference.get(10, TimeUnit.SECONDS);
                assertThat(deleteCode).isIn(200, 409); assertThat(refCode).isIn(200, 404, 409);
                assertThat(deleteCode == 200 && refCode == 200).isFalse();
                long activeRefs = jdbc.queryForObject("SELECT COUNT(*) FROM t_product WHERE category_id=? AND deleted=0", Long.class, target)
                        + jdbc.queryForObject("SELECT COUNT(*) FROM t_category WHERE parent_id=? AND deleted=0", Long.class, target);
                if (deleteCode == 200) { assertThat(activeRefs).isZero(); }
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void inventoryChangeBeforeLockCannotBeOverwrittenByStalePut() throws Exception {
        long id = product("p", null); var pool = Executors.newSingleThreadExecutor();
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE t_product_sku SET stock=9,sales=5 WHERE product_id=" + id);
                var dto = new ProductUpdateDTO(); dto.setId(id); dto.setName("stale"); dto.setStock(10);
                var update = pool.submit(() -> code(() -> products.updateProduct(dto)));
                awaitDatabaseLockWait(); blocker.commit();
                assertThat(update.get(8, TimeUnit.SECONDS)).isEqualTo(409);
                var row = products.getProductById(id);
                assertThat(row.getName()).isEqualTo("p"); assertThat(row.getStock()).isEqualTo(9);
                assertThat(row.getOrderCount()).isEqualTo(5);
            } finally { blocker.rollback(); }
        } finally { pool.shutdownNow(); }
    }

    @Test void changedProductCategoryAbortsAfterLockInsteadOfUsingOldChain() throws Exception {
        long a = category("a", 0), b = category("b", 0), id = product("p", a);
        var pool = Executors.newSingleThreadExecutor();
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeUpdate("UPDATE t_product SET category_id=" + b + " WHERE id=" + id);
                var dto = new ProductUpdateDTO(); dto.setId(id); dto.setName("stale");
                var update = pool.submit(() -> code(() -> products.updateProduct(dto)));
                awaitDatabaseLockWait(); blocker.commit();
                assertThat(update.get(8, TimeUnit.SECONDS)).isEqualTo(409);
                assertThat(products.getProductById(id).getName()).isEqualTo("p");
                assertThat(products.getProductById(id).getCategoryId()).isEqualTo(b);
            } finally { blocker.rollback(); }
        } finally { pool.shutdownNow(); }
    }

    @Test void negativeHistoricalCountersAreNotSilentlyReturnedOrRepaired() throws Exception {
        long id = product("bad", null);
        jdbc.update("UPDATE t_product_sku SET sales=-1 WHERE product_id=? AND is_default=1", id);
        api("GET", "/product/" + id, null, 500);
        api("PUT", "/product", "{\"id\":" + id + ",\"name\":\"repair\"}", 500);
        assertThat(jdbc.queryForObject("SELECT sales FROM t_product_sku WHERE product_id=? AND is_default=1", Integer.class, id)).isEqualTo(-1);
    }

    @Test void skuCombinationsNormalizeAndCannotBeDuplicatedOrReusedAfterDeletion() throws Exception {
        long pid=product("phone",null);
        var created=api("POST","/product/"+pid+"/skus","{\"name\":\"black256\",\"specs\":{\"颜色\":\" 黑色 \",\"容量\":\"256GB\"},\"price\":2.50,\"stock\":3}",200);
        long sid=created.get("id").asLong();
        api("POST","/product/"+pid+"/skus","{\"name\":\"dup\",\"specs\":{\"容量\":\"256GB\",\"颜色\":\"黑色\"},\"price\":2,\"stock\":1}",409);
        api("PUT","/product/"+pid+"/skus/"+sid,"{\"stock\":99}",409);
        api("PUT","/product/"+pid+"/skus/"+sid,"{\"price\":0.50}",200);
        var detail=api("GET","/product/"+pid,null,200);
        assertThat(detail.get("price").decimalValue()).isEqualByComparingTo("0.50");assertThat(detail.get("stock").asLong()).isEqualTo(13);
        api("DELETE","/product/"+pid+"/skus/"+sid,null,200);
        api("POST","/product/"+pid+"/skus","{\"name\":\"dup\",\"specs\":{\"容量\":\"256GB\",\"颜色\":\"黑色\"},\"price\":2,\"stock\":1}",409);
        long defaultId=jdbc.queryForObject("SELECT id FROM t_product_sku WHERE product_id=? AND is_default=1",Long.class,pid);
        api("DELETE","/product/"+pid+"/skus/"+defaultId,null,409);
        api("PUT","/product/"+pid+"/skus/"+defaultId,"{\"specs\":{\"颜色\":\"黑\"}}",409);
    }

    @Test void skuManagementUsesLocalAdminAndChecksOwnershipAndSpecBoundaries() throws Exception {
        long pid=product("p",null),other=product("q",null);
        String route="/product/"+pid+"/skus";
        String valid="{\"name\":\"n\",\"specs\":{\"颜色\":\"白色\"},\"price\":1,\"stock\":1}";
        response(post(route).contentType(MediaType.APPLICATION_JSON).content(valid),401);
        for(String specs:List.of("{}","{\"a\":null}","{\"a\":\" \"}","{\"a\":\"v\",\" a \":\"x\"}"))
            api("POST",route,"{\"name\":\"n\",\"specs\":"+specs+",\"price\":1,\"stock\":1}",400);
        var sku=api("POST",route,valid,200);
        api("PUT","/product/"+other+"/skus/"+sku.get("id").asLong(),"{\"name\":\"x\"}",404);
        api("PUT",route+"/"+sku.get("id").asLong(),"{\"status\":0}",200);
        assertThat(api("GET","/product/"+pid,null,200).get("skus")).hasSize(1);
    }

    @Test void productAndDefaultSkuInsertAreAtomicOnRealSqlFailure() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_sku BEFORE INSERT ON t_product_sku FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only sku failure'");
        try{api("POST","/product","{\"name\":\"atomic\",\"price\":1,\"stock\":3}",500);}
        finally{jdbc.execute("DROP TRIGGER fail_sku");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_product_sku",Long.class)).isZero();
    }

    private List<String> writeRoutes() {
        return List.of("POST /product", "PUT /product", "DELETE /product/1", "POST /product/1/attrs",
                "POST /category", "PUT /category", "DELETE /category/1");
    }
    private JsonNode api(String method, String path, String body, int expected) throws Exception {
        var req = request(HttpMethod.valueOf(method), path).header("X-Local-Admin-Key", KEY).header("Authorization", "invalid-expired-token");
        if (body != null) { req.contentType(MediaType.APPLICATION_JSON).content(body); }
        return response(req, expected).get("data");
    }
    private JsonNode response(MockHttpServletRequestBuilder req, int expected) throws Exception {
        var result = mvc.perform(req).andReturn().getResponse();
        var body = json.readTree(result.getContentAsByteArray());
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(expected);
        assertThat(body.get("code").asInt()).isEqualTo(expected);
        assertThat(result.getHeader("X-Trace-Id")).hasSize(32);
        if (expected != 200) { assertThat(body.get("data").isNull()).isTrue(); }
        return body;
    }
    private long category(String name, long parent) {
        return insert("INSERT INTO t_category(name,parent_id) VALUES (?,?)", name, parent);
    }
    private long product(String name, Long category) {
        long pid=insert("INSERT INTO t_product(name,category_id,price,stock,sales) VALUES (?,?,1.00,10,4)", name, category);
        jdbc.update("INSERT INTO t_product_sku(product_id,name,spec_json,spec_hash,price,stock,sales,is_default) SELECT id,'default','{}',SHA2('{}',256),price,stock,sales,1 FROM t_product WHERE id=?",pid);
        return pid;
    }
    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private List<ProductAttrSaveDTO> batch(String... names) {
        return Arrays.stream(names).map(name -> {
            var dto = new ProductAttrSaveDTO(); dto.setAttrName(name); dto.setAttrValue("value"); return dto;
        }).toList();
    }
    private CategoryUpdateDTO categoryUpdate(long id, long parent) {
        var dto = new CategoryUpdateDTO(); dto.setId(id); dto.setParentId(parent); return dto;
    }
    private int code(Runnable action) {
        try { action.run(); return 200; } catch (BusinessException failure) { return failure.getCode(); }
    }
    private void awaitDatabaseLockWait() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class) > 0) { return; }
            Thread.sleep(10);
        }
        throw new AssertionError("The competing transaction never entered a real database lock wait");
    }
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
