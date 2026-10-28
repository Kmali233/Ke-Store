package com.xiaoke.common.auth;

import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminInterceptorTest {
    static class Endpoints {
        @AdminRequired public void protectedIdentity() { }
        public void publicLogin() { }
    }
    private HandlerMethod method(String name) throws Exception {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(name));
    }
    @AfterEach void clean() { AdminContext.clear(); }

    @Test void annotationFailsClosedWhenAdminAuthenticationIsDisabled() throws Exception {
        var handler = method("protectedIdentity");
        assertThatThrownBy(() -> new AdminInterceptor(null).preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler)).isInstanceOf(BusinessException.class);
    }

    @Test void publicLoginDoesNotAuthenticateAnExpiredCredential() throws Exception {
        var authentication = mock(AdminAuthentication.class);
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "expired");
        assertThat(new AdminInterceptor(authentication).preHandle(request, new MockHttpServletResponse(), method("publicLogin"))).isTrue();
        verifyNoInteractions(authentication);
    }

    @Test void verifiedAdministratorUsesOnlyAdminContextAndCompletionClearsIt() throws Exception {
        var authentication = mock(AdminAuthentication.class);
        var principal = new AdminPrincipal(1, "ops_admin", 0);
        when(authentication.authenticate("synthetic-token")).thenReturn(principal);
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "synthetic-token");
        var response = new MockHttpServletResponse();
        var handler = method("protectedIdentity");
        var interceptor = new AdminInterceptor(authentication);
        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(AdminContext.requirePrincipal()).isEqualTo(principal);
        assertThatThrownBy(UserContext::requirePrincipal).isInstanceOf(BusinessException.class);
        interceptor.afterCompletion(request, response, handler, new IllegalStateException());
        assertThatThrownBy(AdminContext::requirePrincipal).isInstanceOf(BusinessException.class);
    }
}
