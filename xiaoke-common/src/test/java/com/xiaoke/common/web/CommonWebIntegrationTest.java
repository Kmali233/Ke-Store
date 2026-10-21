package com.xiaoke.common.web;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.xiaoke.common.auth.*;
import com.xiaoke.common.config.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.exception.GlobalExceptionHandler;
import com.xiaoke.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CommonWebIntegrationTest.App.class, properties = {
        "xiaoke.jwt.enabled=true", "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "server.address=127.0.0.1", "xiaoke.local-admin-enabled=true", "xiaoke.local-admin-key=test-admin-only",
        "xiaoke.web.allowed-origins=http://localhost:5173", "xiaoke.web.max-request-body-bytes=256"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CommonWebIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @Import({Core.class, DemoController.class})
    static class App { }

    @Import({JwtProperties.class, JwtService.class, TimeConfig.class, CommonWebProperties.class,
            LocalAdminProperties.class, AuthWebMvcConfig.class, AuthStartupValidator.class,
            CorsConfig.class, JacksonInputConfig.class, GlobalExceptionHandler.class,
            RequestBodyLimitAdvice.class, RequestContextCleanupFilter.class, TraceFilter.class})
    static class Core { }

    @RestController
    @RequestMapping("/test")
    static class DemoController {
        static final AtomicInteger WRITES = new AtomicInteger();
        @GetMapping("/open")
        Result<String> open() { return Result.success("public"); }
        @LoginRequired @GetMapping("/me")
        Result<Long> me() { return Result.success(UserContext.requireUserId()); }
        @LoginRequired @GetMapping("/exception")
        Result<Void> exception() { throw new IllegalStateException("private-password-and-address"); }
        @GetMapping("/private-business-error")
        Result<Void> privateBusinessError() { throw new BusinessException("private-password-and-address"); }
        @LocalAdminOnly @PostMapping("/admin")
        Result<Input> admin(@Valid @RequestBody Input input) { WRITES.incrementAndGet(); return Result.success(input); }
        @PostMapping("/input")
        Result<Input> input(@Valid @RequestBody Input input) { return Result.success(input); }
        @GetMapping("/page") @AllowedQuery({"pageNum", "pageSize"})
        Result<Long> page(@Valid PageQuery page) { page.validatePage(); return Result.success(page.getPageSize()); }
        @GetMapping("/id/{id}")
        Result<Long> id(@PathVariable Long id) { return Result.success(id); }
        @GetMapping("/failure/{code}")
        Result<Void> failure(@PathVariable int code) { throw new BusinessException(code, "测试业务错误"); }
    }

    record Input(@NotNull Long id, Integer count, @Digits(integer=8, fraction=2) BigDecimal price,
                 Boolean active, String label, List<@NotNull @Valid Child> children) { }
    record Child(@NotNull String name) { }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @AfterEach
    void cleaned() {
        assertThrows(BusinessException.class, UserContext::requireUserId);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void publicAndAuthenticatedRequestsNeverShareIdentity() throws Exception {
        UserContext.set(new AuthPrincipal(999L, "stale"));
        var first = mvc.perform(get("/test/open").header("Authorization", "garbage")
                        .header("X-Trace-Id", "untrusted-trace"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        assertThat(first.getResponse().getHeader("X-Trace-Id")).matches("[a-f0-9]{32}");
        mvc.perform(get("/test/me").header("Authorization", jwt.issue(42L, "kevin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(42));
        mvc.perform(get("/test/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/test/me").header("Authorization", "Bearer " + jwt.issue(42L, "kevin")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/test/me").header("Authorization", jwt.issue(43L, "alice")))
                .andExpect(jsonPath("$.data").value(43));
        mvc.perform(get("/test/exception").header("Authorization", jwt.issue(42L, "kevin")))
                .andExpect(status().isInternalServerError()).andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
    }

    @Test
    void adminRunsBeforeBodyBindingAndDoesNotRequireBuyerToken() throws Exception {
        int before = DemoController.WRITES.get();
        mvc.perform(post("/test/admin").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());
        assertThat(DemoController.WRITES.get()).isEqualTo(before);
        mvc.perform(post("/test/admin").header("X-Local-Admin-Key", "test-admin-only")
                        .header("Authorization", "garbage").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
                .andExpect(status().isOk());
        assertThat(DemoController.WRITES.get()).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"1\"}", "{\"id\":1.0}", "{\"id\":true}", "{\"id\":1,\"active\":1}",
            "{\"id\":1,\"active\":\"true\"}", "{\"id\":1,\"label\":2}", "{\"id\":1,\"price\":\"1.20\"}",
            "{\"id\":1,\"price\":1.234}", "{\"id\":1,\"userId\":2}", "{\"id\":1,\"id\":2}",
            "{\"id\":1} {}", "{\"id\":1,\"children\":[null]}", "{\"id\":1,\"children\":[{\"name\":null}]}",
            "{\"id\":1,\"children\":[{\"name\":\"x\",\"unknown\":1}]}", "null", "{"})
    void rejectsInvalidJson(String body) throws Exception {
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void decimalValuesStayExactAndNullFieldsArePresent() throws Exception {
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"price\":99999999.99,\"active\":true,\"label\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(99999999.99))
                .andExpect(jsonPath("$.data.label").value(org.hamcrest.Matchers.nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?pageSize=101", "?pageSize=", "?pageNum=0", "?pageNum=-1", "?pageNum=abc",
            "?pageNum=1.0", "?pageSize=1&pageSize=2", "?userId=1", "?pageNum=9223372036854775807&pageSize=100"})
    void rejectsIllegalPagination(String query) throws Exception {
        mvc.perform(get("/test/page" + query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void validatesRoutesBodyLimitAndProtocolHeaders() throws Exception {
        mvc.perform(get("/test/id/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));
        mvc.perform(get("/test/page")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(10));
        for (String id : List.of("abc", "-1", "0", "9223372036854775808")) {
            mvc.perform(get("/test/id/" + id)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/test/absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(patch("/test/open")).andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"));
        mvc.perform(post("/test/input").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415));
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"label\":\"" + "a".repeat(300) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void businessStatusMatchesBody(int code) throws Exception {
        mvc.perform(get("/test/failure/" + code)).andExpect(status().is(code))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void corsAllowsOnlyExactOriginAndPreflightDoesNotWrite() throws Exception {
        int before = DemoController.WRITES.get();
        mvc.perform(options("/test/admin").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,Authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        assertThat(DemoController.WRITES.get()).isEqualTo(before);
        mvc.perform(get("/test/open").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void logsNeverContainCredentialsBodyOrExceptionMessages() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "private-token").header("X-Local-Admin-Key", "private-key")
                            .content("{\"id\":1,\"label\":\"private-receiver-address\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/test/exception").header("Authorization", jwt.issue(42L, "kevin")))
                    .andExpect(status().isInternalServerError());
            mvc.perform(get("/test/private-business-error"))
                    .andExpect(status().isInternalServerError()).andExpect(content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
            assertThat(appender.list).isNotEmpty();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("private-token", "private-key",
                        "private-receiver-address", "private-password-and-address");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
