package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LoginInterceptorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final JwtService jwtService = new JwtService(props(), Clock.fixed(T0, ZoneOffset.UTC));
    private final LoginInterceptor interceptor = new LoginInterceptor(jwtService);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static JwtProperties props() {
        JwtProperties p = new JwtProperties();
        p.setEnabled(true);
        p.setSecretBase64(SECRET);
        p.setIssuer("xiaoke-mall");
        p.setExpirySeconds(7200);
        return p;
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 测试用控制器：一个公开方法、一个要求登录的方法 */
    static class DemoController {
        public void open() {
        }

        @LoginRequired
        public void secured() {
        }
    }

    /** 类级注解、方法上没有注解的控制器 */
    @LoginRequired
    static class SecuredController {
        public void inherited() {
        }
    }

    private HandlerMethod handler(Class<?> type, String methodName) throws Exception {
        Object bean = type.getDeclaredConstructor().newInstance();
        Method method = type.getMethod(methodName);
        return new HandlerMethod(bean, method);
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        return req;
    }

    @Test
    @DisplayName("L1 公开接口不带凭证：放行且不建立身份")
    void l1_publicWithoutToken() throws Exception {
        assertTrue(interceptor.preHandle(request(null), response, handler(DemoController.class, "open")));
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());
    }

    @Test
    @DisplayName("L2 公开接口带垃圾 token：仍然放行（T09）")
    void l2_publicWithGarbageToken() throws Exception {
        assertTrue(interceptor.preHandle(request("garbage.token.value"), response,
                handler(DemoController.class, "open")));
    }

    @Test
    @DisplayName("L3 受保护接口无 Authorization：401")
    void l3_securedWithoutToken() throws Exception {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request(null), response, handler(DemoController.class, "secured")));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("L4 受保护接口带合法 token：放行且身份就位")
    void l4_securedWithValidToken() throws Exception {
        String token = jwtService.issue(42L, "kevin");
        assertTrue(interceptor.preHandle(request(token), response, handler(DemoController.class, "secured")));
        assertEquals(42L, UserContext.requireUserId());
    }

    @Test
    @DisplayName("L5 受保护接口带非法 token：401 且清空旧身份，不留半初始化状态")
    void l5_securedWithBadTokenClearsContext() throws Exception {
        UserContext.set(new AuthPrincipal(1L, "stale"));       // 先塞一个陈旧身份
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("garbage"), response, handler(DemoController.class, "secured")));
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());
    }

    @Test
    @DisplayName("L6 类级 @LoginRequired、方法无注解：仍受保护")
    void l6_classLevelAnnotation() throws Exception {
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request(null), response, handler(SecuredController.class, "inherited")));
    }

    @Test
    @DisplayName("L7 afterCompletion 后身份被清空")
    void l7_afterCompletionClears() throws Exception {
        HandlerMethod hm = handler(DemoController.class, "secured");
        String token = jwtService.issue(42L, "kevin");
        interceptor.preHandle(request(token), response, hm);
        assertEquals(42L, UserContext.requireUserId());

        interceptor.afterCompletion(request(token), response, hm, null);
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());
    }

    @Test
    @DisplayName("L8 同一线程不串号：清空后取身份必须 401（T08）")
    void l8_threadNotLeaking() throws Exception {
        HandlerMethod hm = handler(DemoController.class, "secured");
        interceptor.preHandle(request(jwtService.issue(7L, "kevin")), response, hm);
        assertEquals(7L, UserContext.requireUserId());          // 第一个请求：有身份

        UserContext.clear();                                     // 模拟最外层过滤器在下一个请求入口清空
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());   // 第二个请求：必须没有
    }
}
