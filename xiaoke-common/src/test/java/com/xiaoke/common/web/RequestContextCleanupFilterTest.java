package com.xiaoke.common.web;

import com.xiaoke.common.auth.AuthPrincipal;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextCleanupFilterTest {

    private final RequestContextCleanupFilter filter = new RequestContextCleanupFilter();

    private void run(FilterChain chain) throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("F1 入口先清：调用前残留的旧身份不会进入本次请求")
    void f1_clearsBeforeChain() throws Exception {
        UserContext.set(new AuthPrincipal(1L, "stale"));
        run((req, res) -> assertThrows(BusinessException.class, () -> UserContext.requireUserId()));
    }

    @Test
    @DisplayName("F2 正常返回后清空")
    void f2_clearsAfterNormalReturn() throws Exception {
        run((req, res) -> UserContext.set(new AuthPrincipal(2L, "kevin")));
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());
    }

    @Test
    @DisplayName("F3 业务抛异常后仍然清空（finally 覆盖）")
    void f3_clearsAfterException() {
        assertThrows(IllegalStateException.class, () -> run((req, res) -> {
            UserContext.set(new AuthPrincipal(3L, "kevin"));
            throw new IllegalStateException("boom");
        }));
        assertThrows(BusinessException.class, () -> UserContext.requireUserId());
    }
}
