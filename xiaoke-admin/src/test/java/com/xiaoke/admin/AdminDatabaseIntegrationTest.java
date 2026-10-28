package com.xiaoke.admin;

import com.fasterxml.jackson.databind.*;
import com.xiaoke.common.auth.*;
import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.admin.bootstrap.AdminProvisioning;
import com.xiaoke.admin.repository.AdminAccountStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import jakarta.validation.Validator;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named = "XIAOKE_ADMIN_DB_TESTS", matches = "true")
@SpringBootTest(classes = AdminApplication.class, properties = {
        "spring.datasource.url=${XIAOKE_ADMIN_TEST_DB_URL}", "spring.datasource.username=root", "spring.datasource.password=",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "xiaoke.admin.enabled=true",
        "xiaoke.admin.secret-base64=YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=",
        "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminDatabaseIntegrationTest {
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired Validator validator;
    JwtService buyerJwt;
    @Autowired AdminAccountStore accounts;
    private final String password = "Synthetic-Admin-123";

    @BeforeAll void schema() throws Exception {
        try (var connection = source.getConnection()) {
            if (!connection.getCatalog().matches("xiaoke_admin_verify_[a-z0-9_]+")) {
                throw new IllegalStateException("Refusing non-verification database");
            }
        }
        new ResourceDatabasePopulator(new ClassPathResource("admin-schema.sql")).execute(source);
        var buyerProps = new JwtProperties();
        buyerProps.setSecretBase64("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        buyerJwt = new JwtService(buyerProps, java.time.Clock.systemUTC());
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM t_admin_account");
        jdbc.update("INSERT INTO t_admin_account(id,username,password) VALUES(1,'ops_admin',?)", passwords.encode(password));
    }
    @AfterEach void noIdentityLeak() {
        assertThatThrownBy(AdminContext::requirePrincipal).isInstanceOf(BusinessException.class);
        assertThatThrownBy(UserContext::requirePrincipal).isInstanceOf(BusinessException.class);
    }
    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int status) throws Exception {
        var response = mvc.perform(b).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }
    private String login() throws Exception {
        return request(post("/admin/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", " OPS_ADMIN ", "password", password))), 200)
                .path("data").path("token").asText();
    }
    @Test void loginMeLogoutRevokesAllSessionsAndHidesPrivateFields() throws Exception {
        String first = login(), second = login();
        assertThat(first).isNotEqualTo(second);
        var result = mvc.perform(get("/admin/auth/me").header("Authorization", first)).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(result.getContentAsString()).get("data").size()).isEqualTo(2);
        assertThat(result.getContentAsString()).doesNotContain("password", "tokenVersion", "$2a$");
        request(post("/admin/auth/logout").header("Authorization", first), 200);
        for (String token : List.of(first, second)) {
            request(get("/admin/auth/me").header("Authorization", token), 401);
        }
        request(get("/admin/auth/me").header("Authorization", login()), 200);
    }
    @Test void identitiesNeverCrossAndThereIsNoPublicRegistration() throws Exception {
        request(get("/admin/auth/me").header("Authorization", buyerJwt.issue(1L, "ops_admin")), 401);
        request(get("/user/me").header("Authorization", login()), 404);
        request(post("/user/login").contentType("application/json").content("{}"), 404);
        request(post("/admin/auth/register").contentType("application/json").content("{}"), 404);
        request(get("/admin/auth/me"), 401);
        request(get("/admin/auth/me").header("Authorization", "Bearer " + login()), 401);
    }
    @Test void passwordFailuresStatusDeletionAndVersionsTakeEffectImmediately() throws Exception {
        request(post("/admin/auth/login").contentType("application/json").content("{\"username\":\"ops_admin\",\"password\":\"wrong-password-123\"}"), 401);
        request(post("/admin/auth/login").contentType("application/json").content("{\"username\":\"unknown_admin\",\"password\":\"wrong-password-123\"}"), 401);
        String token = login();
        jdbc.update("UPDATE t_admin_account SET status=0 WHERE id=1");
        request(get("/admin/auth/me").header("Authorization", token), 401);
        request(post("/admin/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", "ops_admin", "password", password))), 401);
        jdbc.update("UPDATE t_admin_account SET status=1,token_version=token_version+1 WHERE id=1");
        request(get("/admin/auth/me").header("Authorization", token), 401);
        String newer = login();
        jdbc.update("UPDATE t_admin_account SET deleted=1 WHERE id=1");
        request(get("/admin/auth/me").header("Authorization", newer), 401);
    }
    @Test void rejectsUnknownFieldsQueryPollutionAndMalformedInputs() throws Exception {
        for (String body : List.of("{}", "{\"username\":\"ops_admin\",\"password\":\"short\"}",
                "{\"username\":\"ops_admin\",\"password\":\"Synthetic-Admin-123\",\"role\":\"root\"}")) {
            request(post("/admin/auth/login").contentType("application/json").content(body), 400);
        }
        request(get("/admin/auth/me?adminId=2").header("Authorization", login()), 400);
    }
    @Test void concurrentLogoutAdvancesVersionOnlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try { accounts.revoke(new AdminPrincipal(1, "ops_admin", 0)); return true; }
                    catch (BusinessException ex) { assertThat(ex.getCode()).isEqualTo(401); return false; }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var future : futures) { if (future.get(15, TimeUnit.SECONDS)) { successful++; } }
            assertThat(successful).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT token_version FROM t_admin_account WHERE id=1", Long.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    private AdminProvisioning provision(String mode, String username, String password) {
        var env = new MockEnvironment().withProperty("spring.main.web-application-type", "none")
                .withProperty("xiaoke.admin.provision.mode", mode).withProperty("xiaoke.admin.provision.username", username)
                .withProperty("xiaoke.admin.provision.password", password);
        return new AdminProvisioning(env, jdbc, passwords, validator);
    }
    @Test void explicitProvisioningNeverOverwritesAndRecoveryRevokesWithoutEnabling() throws Exception {
        var args = new DefaultApplicationArguments(new String[0]);
        assertThatThrownBy(() -> provision("create", "other_admin", password).run(args)).isInstanceOf(IllegalStateException.class);
        String token = login();
        provision("recover", "ops_admin", "Recovered-Admin-456").run(args);
        request(get("/admin/auth/me").header("Authorization", token), 401);
        assertThat(passwords.matches("Recovered-Admin-456", jdbc.queryForObject("SELECT password FROM t_admin_account WHERE id=1", String.class))).isTrue();
        jdbc.update("UPDATE t_admin_account SET status=0 WHERE id=1");
        provision("recover", "ops_admin", password).run(args);
        assertThat(jdbc.queryForObject("SELECT status FROM t_admin_account WHERE id=1", Integer.class)).isZero();
        jdbc.update("DELETE FROM t_admin_account");
        provision("create", "first_admin", password).run(args);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> provision("create", "another_admin", password).run(args)).isInstanceOf(IllegalStateException.class);
    }
    @Test void provisioningRejectsWebStartupMissingAndWeakCredentials() {
        var args = new DefaultApplicationArguments(new String[0]);
        assertThatThrownBy(() -> new AdminProvisioning(new MockEnvironment(), jdbc, passwords, validator).run(args))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("offline");
        assertThatThrownBy(() -> provision("create", "ops_admin", "123456789012").run(args)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provision("recover", "missing_admin", password).run(args)).isInstanceOf(IllegalStateException.class);
    }
}
