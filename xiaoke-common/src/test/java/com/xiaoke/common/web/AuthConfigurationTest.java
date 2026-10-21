package com.xiaoke.common.web;

import com.xiaoke.common.auth.*;
import com.xiaoke.common.config.CommonWebProperties;
import com.xiaoke.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(CommonWebIntegrationTest.App.class);

    @Test
    void disabledJwtWithProtectedHandlerRefusesStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("LoginRequired");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64!", "c2hvcnQ="})
    void badSecretRefusesStartup(String secret) {
        runner.withPropertyValues("xiaoke.jwt.enabled=true", "xiaoke.jwt.secret-base64=" + secret)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledJwtWithOnlyPublicAndAdminEndpointsStartsAndKeepsAdminClosed() {
        new WebApplicationContextRunner().withUserConfiguration(PublicApp.class).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .webAppContextSetup(context).build();
            mvc.perform(get("/public")).andExpect(status().isOk());
            mvc.perform(get("/admin")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
            mvc.perform(get("/public").header("Origin", "http://localhost:5173"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        });
    }

    @Test
    void conflictingAnnotationsRefuseStartupEvenWithJwtEnabled() {
        new WebApplicationContextRunner().withUserConfiguration(ConflictApp.class)
                .withPropertyValues("xiaoke.jwt.enabled=true",
                        "xiaoke.jwt.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("LocalAdminOnly");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "noDev", "prod", "mixed", "wildcardBind", "ipv6", "remote", "missingSecret"})
    void adminChecksEnvironmentAndDirectPeerBeforeKey(String scenario) throws Exception {
        var props = new LocalAdminProperties();
        props.setLocalAdminEnabled(!scenario.equals("closed"));
        props.setLocalAdminKey(scenario.equals("missingSecret") ? null : " exact-key ");
        var env = new MockEnvironment().withProperty("server.address", scenario.equals("wildcardBind") ? "0.0.0.0" : "127.0.0.1");
        env.setActiveProfiles(switch (scenario) {
            case "noDev" -> new String[]{"test"};
            case "prod" -> new String[]{"prod"};
            case "mixed" -> new String[]{"dev", "prod"};
            default -> new String[]{"dev"};
        });
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(scenario.equals("ipv6") ? "::1" : scenario.equals("remote") ? "192.0.2.1" : "127.0.0.1");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Local-Admin-Key", " exact-key ");
        var handler = new HandlerMethod(new PublicController(), PublicController.class.getDeclaredMethod("admin"));
        var interceptor = new LocalAdminInterceptor(props, env);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler)).getCode());
    }

    @Test
    void adminKeyIsComparedWithoutTrimming() throws Exception {
        var props = new LocalAdminProperties();
        props.setLocalAdminEnabled(true); props.setLocalAdminKey(" exact-key ");
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1"); env.setActiveProfiles("dev");
        var interceptor = new LocalAdminInterceptor(props, env);
        var handler = new HandlerMethod(new PublicController(), PublicController.class.getDeclaredMethod("admin"));
        var request = new MockHttpServletRequest(); request.addHeader("X-Local-Admin-Key", "exact-key");
        assertEquals(401, assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler)).getCode());
        request.removeHeader("X-Local-Admin-Key"); request.addHeader("X-Local-Admin-Key", " exact-key ");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
    }

    @Test
    void invalidCorsAndBodyLimitsRefuseConfiguration() {
        var props = new CommonWebProperties(); props.setAllowedOrigins(java.util.List.of("*"));
        assertThrows(IllegalStateException.class, props::validate);
        props.setAllowedOrigins(java.util.List.of()); props.setMaxRequestBodyBytes(0);
        assertThrows(IllegalStateException.class, props::validate);
    }

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class})
    @Import({CommonWebIntegrationTest.Core.class, PublicController.class})
    static class PublicApp { }

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class})
    @Import({CommonWebIntegrationTest.Core.class, ConflictController.class})
    static class ConflictApp { }

    @RestController
    static class PublicController {
        @GetMapping("/public") String open() { return "public"; }
        @LocalAdminOnly @GetMapping("/admin") String admin() { return "admin"; }
    }

    @RestController @LoginRequired
    static class ConflictController {
        @LocalAdminOnly @GetMapping("/conflict") String conflict() { return "invalid"; }
    }
}
