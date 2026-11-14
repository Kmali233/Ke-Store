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
    @Autowired com.xiaoke.audit.AuditWriter audit;
    JwtService buyerJwt;
    @Autowired AdminAccountStore accounts;
    @Autowired com.xiaoke.admin.service.AdminAccessService access;
    @Autowired AdminJwtService adminJwt;
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
        jdbc.update("DELETE FROM t_admin_audit");
        jdbc.update("DELETE FROM t_admin_account_role");
        jdbc.update("DELETE FROM t_admin_account");
        jdbc.update("INSERT INTO t_admin_account(id,username,password) VALUES(1,'ops_admin',?)", passwords.encode(password));
        jdbc.update("INSERT INTO t_admin_account_role(admin_id,role_id) VALUES(1,1)");
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
        return new AdminProvisioning(env, jdbc, passwords, validator, audit);
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
        jdbc.update("DELETE FROM t_admin_account_role");
        jdbc.update("DELETE FROM t_admin_account");
        provision("create", "first_admin", password).run(args);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> provision("create", "another_admin", password).run(args)).isInstanceOf(IllegalStateException.class);
    }
    @Test void provisioningRejectsWebStartupMissingAndWeakCredentials() {
        var args = new DefaultApplicationArguments(new String[0]);
        assertThatThrownBy(() -> new AdminProvisioning(new MockEnvironment(), jdbc, passwords, validator, audit).run(args))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("offline");
        assertThatThrownBy(() -> provision("create", "ops_admin", "123456789012").run(args)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provision("recover", "missing_admin", password).run(args)).isInstanceOf(IllegalStateException.class);
    }

    private long createAccount(String token,String name) throws Exception {
        return request(post("/admin/access/accounts").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",name,"password",password))),200).path("data").path("id").asLong();
    }
    private String token(long id,String name) { return adminJwt.issue(new AdminPrincipal(id,name,0)); }
    private void assign(String token,long id,List<Long> roles,int status) throws Exception {
        request(put("/admin/access/accounts/"+id+"/roles").header("Authorization",token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("roleIds",roles))),status);
    }
    @Test void fourRolesAccountCreationAndReplacementAreExplicitAndPrivate() throws Exception {
        String root=login();
        var roles=request(get("/admin/access/roles").header("Authorization",root),200).path("data");
        assertThat(roles.size()).isEqualTo(4);
        long id=createAccount(root,"new_admin"); String other=token(id,"new_admin");
        assertThat(request(get("/admin/access/me").header("Authorization",other),200).path("data").path("permissions").isEmpty()).isTrue();
        request(get("/admin/access/accounts").header("Authorization",other),403);
        assign(root,id,List.of(2L,3L),200);
        assertThat(accounts.permissions(id)).containsExactlyInAnyOrder("product:write","inventory:adjust","order:read");
        assign(root,id,List.of(4L),200);
        assertThat(accounts.permissions(id)).containsExactlyInAnyOrder("refund:review", "trade:reconcile");
        assign(other,id,List.of(1L),403);
        request(post("/admin/access/accounts").header("Authorization",other).contentType("application/json").content("{broken"),403);
        assign(root,id,List.of(1L),200);
        request(get("/admin/access/accounts").header("Authorization",other),200);
        assign(root,id,List.of(),200);
        request(get("/admin/access/accounts").header("Authorization",other),403);
        var response=mvc.perform(get("/admin/access/accounts").header("Authorization",root)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("password","tokenVersion","$2a$");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void selfChangesInvalidRolesAndUnknownFieldsNeverPartiallyWrite() throws Exception {
        String root=login(); long id=createAccount(root,"new_admin");
        assign(root,1,List.of(),409);
        request(put("/admin/access/accounts/1/status").header("Authorization",root).contentType("application/json").content("{\"status\":0}"),409);
        assign(root,id,List.of(2L),200);
        assign(root,id,List.of(999L),400);
        assign(root,id,List.of(2L,2L),400);
        assertThat(accounts.permissions(id)).containsExactly("product:write");
        assign(root,999,List.of(2L),404);
        for (String body: List.of("{}","{\"roleIds\":[null]}","{\"roleIds\":[1],\"adminId\":1}")) {
            request(put("/admin/access/accounts/"+id+"/roles").header("Authorization",root).contentType("application/json").content(body),400);
        }
        request(get("/admin/access/accounts?adminId=1").header("Authorization",root),400);
        request(post("/admin/access/accounts").header("Authorization",root).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","new_admin","password",password))),409);
    }
    @Test void disablingRevokesAndEnablingNeverResurrectsOldSession() throws Exception {
        String root=login(); long id=createAccount(root,"new_admin"); String old=token(id,"new_admin");
        assign(root,id,List.of(2L),200);
        request(put("/admin/access/accounts/"+id+"/status").header("Authorization",root).contentType("application/json").content("{\"status\":0}"),200);
        request(get("/admin/access/me").header("Authorization",old),401);
        request(put("/admin/access/accounts/"+id+"/status").header("Authorization",root).contentType("application/json").content("{\"status\":1}"),200);
        request(get("/admin/access/me").header("Authorization",old),401);
        assertThat(jdbc.queryForObject("SELECT token_version FROM t_admin_account WHERE id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void assignmentSqlFailureRollsBackDeletionOfPreviousRoles() throws Exception {
        String root=login(); long id=createAccount(root,"new_admin"); assign(root,id,List.of(2L),200);
        jdbc.execute("CREATE TRIGGER verify_role_failure BEFORE INSERT ON t_admin_account_role FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected test failure'");
        try { assign(root,id,List.of(3L),500); }
        finally { jdbc.execute("DROP TRIGGER verify_role_failure"); }
        assertThat(accounts.permissions(id)).containsExactly("product:write");
    }
    @Test void concurrentSystemAdminsCannotDisableEachOtherAndLoseLastAdministrator() throws Exception {
        String root=login(); long id=createAccount(root,"second_admin"); assign(root,id,List.of(1L),200);
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (int i=0;i<2;i++) {
                final int actor=i;
                results.add(pool.submit(() -> {
                    start.await();
                    AdminContext.set(actor==0?new AdminPrincipal(1,"ops_admin",0):new AdminPrincipal(id,"second_admin",0));
                    try { access.status(actor==0?id:1,0); return true; }
                    catch (BusinessException ex) { assertThat(ex.getCode()).isIn(401,403); return false; }
                    finally { AdminContext.clear(); }
                }));
            }
            start.countDown();
            int successful=0; for (var result:results) { if(result.get(15,TimeUnit.SECONDS)) successful++; }
            assertThat(successful).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account WHERE status=1 AND deleted=0",Long.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void queuedRoleWriteRechecksActorAfterAnotherTransactionRevokesIt() throws Exception {
        String root=login(); long id=createAccount(root,"second_admin"); assign(root,id,List.of(1L),200);
        var pool=Executors.newSingleThreadExecutor();
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(var statement=connection.createStatement(); var rs=statement.executeQuery("SELECT id FROM t_admin_role WHERE id=1 FOR UPDATE")) {
                    assertThat(rs.next()).isTrue();
                }
                var result=pool.submit(() -> {
                    AdminContext.set(new AdminPrincipal(id,"second_admin",0));
                    try { access.assign(1,List.of()); return 200; }
                    catch (BusinessException ex) { return ex.getCode(); }
                    finally { AdminContext.clear(); }
                });
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<end) {
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0) { waiting=true; break; }
                    Thread.sleep(10);
                }
                assertThat(waiting).as("role write actually waited for the management lock").isTrue();
                try(var statement=connection.prepareStatement("DELETE FROM t_admin_account_role WHERE admin_id=?")) {
                    statement.setLong(1,id); statement.executeUpdate();
                }
                connection.commit();
                assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(403);
                assertThat(accounts.permissions(1)).contains("admin:manage");
            } finally { connection.rollback(); }
        } finally { pool.shutdownNow(); }
    }
    @Test void initialProvisionRollsBackAccountWhenSystemRoleLinkCannotBeWritten() {
        jdbc.update("DELETE FROM t_admin_account_role"); jdbc.update("DELETE FROM t_admin_account");
        jdbc.execute("CREATE TRIGGER verify_provision_failure BEFORE INSERT ON t_admin_account_role FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected test failure'");
        try {
            assertThatThrownBy(() -> provision("create","first_admin",password).run(new DefaultApplicationArguments(new String[0])))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { jdbc.execute("DROP TRIGGER verify_provision_failure"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account",Long.class)).isZero();
    }

    @Test void everyAccountManagementEndpointRejectsEachNonSystemRoleBeforeBodyParsing() throws Exception {
        String root=login(); long id=createAccount(root,"staff_admin"); String staff=token(id,"staff_admin");
        for (List<Long> roles:List.of(List.of(2L),List.of(3L),List.of(4L),List.<Long>of())) {
            assign(root,id,roles,200);
            request(get("/admin/access/me").header("Authorization",staff),200);
            request(get("/admin/access/roles").header("Authorization",staff),403);
            request(get("/admin/access/accounts").header("Authorization",staff),403);
            request(post("/admin/access/accounts").header("Authorization",staff).contentType("application/json").content("{broken"),403);
            request(put("/admin/access/accounts/"+id+"/roles").header("Authorization",staff).contentType("application/json").content("{broken"),403);
            request(put("/admin/access/accounts/"+id+"/status").header("Authorization",staff).contentType("application/json").content("{broken"),403);
        }
    }
    @Test void accountLimitKeepsListCompleteAndRejectsFurtherCreation() throws Exception {
        String root=login();
        List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<199;i++) rows.add(new Object[]{"capacity_"+i,"unused-test-only"});
        jdbc.batchUpdate("INSERT INTO t_admin_account(username,password) VALUES(?,?)",rows);
        assertThat(request(get("/admin/access/accounts").header("Authorization",root),200).path("data").size()).isEqualTo(200);
        request(post("/admin/access/accounts").header("Authorization",root).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","over_capacity","password",password))),409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account",Long.class)).isEqualTo(200);
    }

    @Test void auditSearchIsPermissionProtectedBoundedAndAppendOnly() throws Exception {
        String root=login(); long id=createAccount(root,"audit_staff"); assign(root,id,List.of(2L),200);
        String staff=token(id,"audit_staff");
        request(get("/admin/audit/page"),401);
        request(get("/admin/audit/page").header("Authorization",buyerJwt.issue(1L,"buyer_one")),401);
        request(get("/admin/audit/page").header("Authorization",staff),403);
        var data=request(get("/admin/audit/page").header("Authorization",root).param("actorId","1")
                .param("targetType","ADMIN_ACCOUNT").param("targetId",Long.toString(id)),200).path("data");
        assertThat(data.path("total").asInt()).isEqualTo(2);
        for(var entry:data.path("records")) {
            assertThat(entry.path("integrityValid").asBoolean()).isTrue();
            assertThat(entry.toString()).doesNotContain(password,"password","tokenVersion");
            assertThat(entry.path("actorId").asLong()).isEqualTo(1);
        }
        for(String query:List.of("pageSize=101","pageNum=1001","actorId=0","action=BOGUS","targetType=USER", "traceId=bad", "unknown=1",
                "from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z", "from=2026-10-29T00:00:00Z&to=2026-10-28T00:00:00Z")) {
            request(get("/admin/audit/page?"+query).header("Authorization",root),400);
        }
        request(delete("/admin/audit/page").header("Authorization",root),405);
        request(put("/admin/audit/page").header("Authorization",root).contentType("application/json").content("{}"),405);
        request(get("/admin/audit/page").header("Authorization",root).param("pageSize","1").param("pageNum","2"),200);
    }
    @Test void auditQueryFlagsModifiedRowsAndWithholdsChangedPayload() throws Exception {
        String root=login(); long id=createAccount(root,"audit_staff");
        jdbc.update("UPDATE t_admin_audit SET changes_json=JSON_OBJECT('password','must-not-return') WHERE target_id=?",Long.toString(id));
        var entry=request(get("/admin/audit/page").header("Authorization",root).param("targetId",Long.toString(id)),200)
                .path("data").path("records").get(0);
        assertThat(entry.path("integrityValid").asBoolean()).isFalse();
        assertThat(entry.path("changes").isNull()).isTrue();
        assertThat(entry.toString()).doesNotContain("must-not-return");
    }
    @Test void auditInsertFailureRollsBackRoleAssignmentAndAccountStatus() throws Exception {
        String root=login(); long id=createAccount(root,"audit_staff"); assign(root,id,List.of(2L),200);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_audit",Long.class);
        jdbc.execute("CREATE TRIGGER verify_audit_failure BEFORE INSERT ON t_admin_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'");
        try {
            assign(root,id,List.of(3L),500);
            request(put("/admin/access/accounts/"+id+"/status").header("Authorization",root)
                    .contentType("application/json").content("{\"status\":0}"),500);
        } finally { jdbc.execute("DROP TRIGGER verify_audit_failure"); }
        assertThat(accounts.permissions(id)).containsExactly("product:write");
        assertThat(jdbc.queryForObject("SELECT status FROM t_admin_account WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT token_version FROM t_admin_account WHERE id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_audit",Long.class)).isEqualTo(count);
    }
    @Test void rollbackAfterAuditWriteLeavesNeitherBusinessNorSuccessEvidence() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO t_admin_account(id,username,password) VALUES(99,'rollback_actor','unused')");
            audit.record(com.xiaoke.audit.AuditAction.ADMIN_CREATE,99,com.xiaoke.audit.AuditChanges.start().put(com.xiaoke.audit.AuditChanges.Field.STATUS,null,1));
            throw new IllegalStateException("synthetic late business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_account WHERE id=99",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_audit",Long.class)).isZero();
    }
}
