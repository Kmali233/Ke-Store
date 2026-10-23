package com.xiaoke.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.user.dto.AddressCreateDTO;
import com.xiaoke.user.dto.RegisterDTO;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.user.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 仅允许专用隔离库，绝不对已有商城库执行清空或建表。 */
@EnabledIfEnvironmentVariable(named = "XIAOKE_USER_DB_TESTS", matches = "true")
@SpringBootTest(classes = UserApplication.class, properties = {
        "spring.datasource.url=${XIAOKE_USER_TEST_DB_URL}", "spring.datasource.username=root",
        "spring.datasource.password=", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2",
        "spring.datasource.hikari.maximum-pool-size=8",
        "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDatabaseIntegrationTest {
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired AddressService addresses;
    @Autowired UserMapper userMapper;
    @Autowired UserTransactions transactions;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    JdbcTemplate jdbc;

    @BeforeAll
    void schema() throws Exception {
        try (var connection = source.getConnection()) {
            if (!connection.getCatalog().matches("xiaoke_user_verify_[a-z0-9_]+")) {
                throw new IllegalStateException("Refusing to modify a non-verification database");
            }
        }
        new ResourceDatabasePopulator(new ClassPathResource("user-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM t_address");
        jdbc.update("DELETE FROM t_user");
    }

    @AfterEach
    void contextWasCleaned() {
        assertThatThrownBy(UserContext::requireUserId).isInstanceOf(BusinessException.class);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void registrationLoginAndMeUseCanonicalNameHashAndWhitelistedVo() throws Exception {
        var registered = request(post("/user/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":" Test_User ","password":" Abc123 ","nickname":" 测试用户 "}
                """), 200);
        assertThat(registered.get("data").isNull()).isTrue();
        var stored = userMapper.findIncludingDeleted("test_user");
        assertThat(passwords.matches(" Abc123 ", stored.getPassword())).isTrue();
        assertThat(stored.getStatus()).isEqualTo(1);
        assertThat(stored.getDeleted()).isZero();
        var logged = request(post("/user/login").header("Authorization", "expired-garbage")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":" TEST_USER ","password":" Abc123 "}
                """), 200).get("data");
        assertThat(logged.get("expiresIn").asLong()).isEqualTo(7200);
        String token = logged.get("token").asText();
        assertThat(jwt.verify(token).userId()).isEqualTo(stored.getId());
        assertThat(logged.get("userInfo").size()).isEqualTo(3);
        var me = request(get("/user/me").header("Authorization", token), 200).get("data");
        assertThat(me.size()).isEqualTo(6);
        assertThat(me.get("phone").isNull()).isTrue();
        assertThat(me.get("createTime").asText()).contains("T");
        assertThat(me.has("password")).isFalse();
        assertThat(me.has("deleted")).isFalse();
        request(get("/user/me").header("Authorization", "Bearer " + token), 401);
        request(get("/user/me"), 401);
    }

    @Test
    void deletedNamesRemainOccupiedAndConcurrentRegistrationHasOneWinner() throws Exception {
        register("taken_user");
        jdbc.update("UPDATE t_user SET deleted=1 WHERE username='taken_user'");
        request(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"TAKEN_USER\",\"password\":\"abc12345\"}"), 400);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                RegisterDTO dto = new RegisterDTO();
                dto.setUsername("racing_user");
                dto.setPassword("abc12345");
                try { users.register(dto); return 200; }
                catch (BusinessException failure) { return failure.getCode(); }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE username='racing_user'", Integer.class))
                    .isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void loginChecksPasswordBeforeStatusAndEveryProtectedEndpointRechecksAccount() throws Exception {
        register("status_user");
        String token = login("status_user");
        long uid = jwt.verify(token).userId();
        jdbc.update("UPDATE t_user SET status=0 WHERE id=?", uid);
        request(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"status_user\",\"password\":\"wrongpass\"}"), 401);
        request(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"status_user\",\"password\":\"abc12345\"}"), 403);
        request(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"absent_user\",\"password\":\"abc12345\"}"), 401);
        for (var entry : protectedRequests()) { request(entry.header("Authorization", token), 403); }
        jdbc.update("UPDATE t_user SET deleted=1 WHERE id=?", uid);
        for (var entry : protectedRequests()) { request(entry.header("Authorization", token), 401); }
        request(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"status_user\",\"password\":\"abc12345\"}"), 401);
    }

    @Test
    void inputContractRejectsForgedOwnerWrongTypesAndBodiesAfterAuthentication() throws Exception {
        request(post("/address").contentType(MediaType.APPLICATION_JSON).content("{"), 401);
        request(put("/address").contentType(MediaType.APPLICATION_JSON).content("{"), 401);
        request(delete("/address/not-a-number"), 401);
        register("input_user");
        String token = login("input_user");
        for (String body : List.of("{\"userId\":1}", "{\"isDefault\":null}", "{\"isDefault\":1}",
                "{\"isDefault\":\"true\"}", "{\"receiverPhone\":1234}")) {
            request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body), 400);
        }
        request(get("/address/list").header("Authorization", token).param("userId", "1"), 400);
        request(get("/address/list").header("Authorization", token).content("{}"), 400);
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            request(delete("/address/" + id).header("Authorization", token), 400);
        }
        request(put("/address/1/default").header("Authorization", token).content("{}"), 400);
        request(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test_x\",\"password\":\"abc12345\",\"status\":1}"), 400);
        request(put("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"1\",\"isDefault\":true}"), 400);
        request(post("/address").header("Authorization", token).contentType(MediaType.TEXT_PLAIN).content("{}"), 415);
        request(patch("/address").header("Authorization", token), 405);
        request(get("/no-such-route"), 404);
        request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(1048577)), 413);
    }

    @Test
    void addressDefaultsReplacementDeletionAndOrderingWork() throws Exception {
        register("address_user");
        String token = login("address_user");
        assertThat(request(get("/address/list").header("Authorization", token), 200).get("data").isEmpty()).isTrue();
        long first = create(token, "{\"province\":\"广东\",\"city\":\"深圳\"}");
        long second = create(token, "{\"isDefault\":false}");
        request(put("/address/" + second + "/default").header("Authorization", token), 200);
        request(put("/address/" + second + "/default").header("Authorization", token), 200);
        var list = request(get("/address/list").header("Authorization", token), 200).get("data");
        assertThat(list.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(list.get(0).has("userId")).isFalse();
        assertThat(list.get(0).has("deleted")).isFalse();
        var updated = request(put("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + first + ",\"receiverName\":\"新收件人\",\"receiverPhone\":\"123\",\"detailAddress\":\"新路\",\"isDefault\":false}"), 200).get("data");
        assertThat(updated.get("province").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT province FROM t_address WHERE id=?", String.class, first)).isNull();
        request(delete("/address/" + second).header("Authorization", token), 200);
        request(delete("/address/" + second).header("Authorization", token), 404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address WHERE deleted=0 AND is_default=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_default FROM t_address WHERE id=?", Integer.class, second)).isZero();
        assertThat(request(get("/address/list").header("Authorization", token), 200).get("data").size()).isEqualTo(1);
    }

    @Test
    void explicitFalseIsRespectedAndLaterOmissionDoesNotInventDefault() throws Exception {
        register("false_user");
        String token = login("false_user");
        create(token, "{\"isDefault\":false}");
        create(token, "{}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address WHERE is_default=1", Integer.class)).isZero();
        long third = create(token, "{\"isDefault\":true}");
        assertThat(request(get("/address/list").header("Authorization", token), 200).get("data").get(0).get("id").asLong())
                .isEqualTo(third);
    }

    @Test
    void otherUsersAddressCannotBeChangedOrUsedToClearOwnDefault() throws Exception {
        register("owner_a"); register("owner_b");
        String a = login("owner_a"), b = login("owner_b");
        long own = create(a, "{}"), other = create(b, "{}");
        request(put("/address/" + other + "/default").header("Authorization", a), 404);
        request(delete("/address/" + other).header("Authorization", a), 404);
        request(put("/address").header("Authorization", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + other + ",\"receiverName\":\"x\",\"receiverPhone\":\"123\",\"detailAddress\":\"x\",\"isDefault\":true}"), 404);
        assertThat(jdbc.queryForObject("SELECT is_default FROM t_address WHERE id=?", Integer.class, own)).isEqualTo(1);
        assertThat(request(get("/address/list").header("Authorization", a), 200).get("data").size()).isEqualTo(1);
    }

    @Test
    void combinedAddressLimitIsEnforcedAtWriteTime() throws Exception {
        register("length_user");
        String token = login("length_user");
        create(token, json.writeValueAsString(Map.of("province", "省", "detailAddress", "路".repeat(253))));
        var body = addressBody();
        body.put("province", "省"); body.put("detailAddress", "路".repeat(254));
        request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDefaultCreationSerializesOnUserRow() throws Exception {
        register("parallel_user");
        long uid = userMapper.findIncludingDeleted("parallel_user").getId();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> attempt = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return addresses.create(uid, addressDto(true)).id();
            };
            var a = executor.submit(attempt); var b = executor.submit(attempt);
            assertThat(a.get(8, TimeUnit.SECONDS)).isNotEqualTo(b.get(8, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address WHERE deleted=0", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address WHERE deleted=0 AND is_default=1", Integer.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void realLockTimeoutReturns409AndNoPartialWrite() throws Exception {
        register("locked_user");
        String token = login("locked_user");
        long uid = jwt.verify(token).userId();
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_user WHERE id=? FOR UPDATE")) {
                statement.setLong(1, uid); statement.executeQuery().close();
                request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(addressBody())), 409);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address", Integer.class)).isZero();
            } finally { blocker.rollback(); }
        }
    }

    @Test
    void failureAfterClearingDefaultRollsEverythingBack() throws Exception {
        register("rollback_user");
        String token = login("rollback_user");
        long first = create(token, "{}");
        jdbc.execute("CREATE TRIGGER verify_user_insert_failure BEFORE INSERT ON t_address FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test insert failure'");
        try {
            var body = addressBody(); body.put("isDefault", true);
            request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)), 500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_address", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT is_default FROM t_address WHERE id=?", Integer.class, first)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER verify_user_insert_failure"); }
    }

    @Test
    void realDeadlockHasOneRolledBackVictimAndNoPartialChanges() throws Exception {
        register("deadlock_a"); register("deadlock_b");
        long a = userMapper.findIncludingDeleted("deadlock_a").getId();
        long b = userMapper.findIncludingDeleted("deadlock_b").getId();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> oppositeUpdates(a, b, barrier));
            var second = executor.submit(() -> oppositeUpdates(b, a, barrier));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForList("SELECT status FROM t_user ORDER BY id", Integer.class)).containsExactly(2, 2);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void oneUsersRowLockDoesNotBlockAnotherUsersAddress() throws Exception {
        register("independent_a"); register("independent_b");
        long a = userMapper.findIncludingDeleted("independent_a").getId();
        long b = userMapper.findIncludingDeleted("independent_b").getId();
        var executor = Executors.newFixedThreadPool(2);
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_user WHERE id=? FOR UPDATE")) {
                statement.setLong(1, a); statement.executeQuery().close();
                var blocked = executor.submit(() -> addresses.create(a, addressDto(false)));
                var independent = executor.submit(() -> addresses.create(b, addressDto(false)));
                assertThat(independent.get(1500, TimeUnit.MILLISECONDS).id()).isPositive();
                blocker.rollback();
                assertThat(blocked.get(4, TimeUnit.SECONDS).id()).isPositive();
            } finally { blocker.rollback(); }
        } finally { executor.shutdownNow(); }
    }

    private int oppositeUpdates(long first, long second, CyclicBarrier barrier) {
        try {
            transactions.execute(() -> {
                jdbc.update("UPDATE t_user SET status=status+1 WHERE id=?", first);
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                jdbc.update("UPDATE t_user SET status=status+1 WHERE id=?", second);
                return null;
            });
            return 200;
        } catch (BusinessException failure) { return failure.getCode(); }
    }

    private List<MockHttpServletRequestBuilder> protectedRequests() throws Exception {
        var update = addressBody(); update.put("id", 1); update.put("isDefault", false);
        return List.of(get("/user/me"), get("/address/list"),
                post("/address").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(addressBody())),
                put("/address").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update)),
                delete("/address/1"), put("/address/1/default"));
    }

    private void register(String username) throws Exception {
        request(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", "abc12345"))), 200);
    }

    private String login(String username) throws Exception {
        return request(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", "abc12345"))), 200)
                .get("data").get("token").asText();
    }

    private long create(String token, String extra) throws Exception {
        var body = addressBody();
        json.readTree(extra).fields().forEachRemaining(entry -> body.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
        return request(post("/address").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), 200).get("data").get("id").asLong();
    }

    private static Map<String, Object> addressBody() {
        return new HashMap<>(Map.of("receiverName", "张三", "receiverPhone", "123", "detailAddress", "测试路1号"));
    }

    private static AddressCreateDTO addressDto(boolean makeDefault) {
        var dto = new AddressCreateDTO();
        dto.setReceiverName("张三"); dto.setReceiverPhone("123"); dto.setDetailAddress("测试路1号");
        dto.setIsDefault(makeDefault);
        return dto;
    }

    private JsonNode request(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expected);
        var value = json.readTree(response.getContentAsByteArray());
        assertThat(value.get("code").asInt()).isEqualTo(expected);
        if (expected >= 400) { assertThat(value.get("data").isNull()).isTrue(); }
        assertThat(response.getHeader("X-Trace-Id")).matches("[a-f0-9]{32}");
        return value;
    }
}
