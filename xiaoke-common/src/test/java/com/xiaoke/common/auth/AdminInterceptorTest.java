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
        @AdminPermission(AdminPermissions.PRODUCT_WRITE) public void product() { }
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

    @Test void permissionIsReadAgainForEachOperationAndMissingLookupFailsClosed() throws Exception {
        var auth=mock(AdminAuthentication.class);
        when(auth.authenticate(any())).thenReturn(new AdminPrincipal(1,"ops_admin",0));
        var lookup=mock(AdminPermissionLookup.class);
        when(lookup.permissions(1)).thenReturn(java.util.Set.of(AdminPermissions.PRODUCT_WRITE),java.util.Set.of());
        var request=new MockHttpServletRequest(); request.addHeader("Authorization","synthetic");
        var interceptor=new AdminInterceptor(auth,lookup);
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),method("product"))).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request,new MockHttpServletResponse(),method("product")))
                .isInstanceOfSatisfying(BusinessException.class,ex -> assertThat(ex.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> new AdminInterceptor(auth).preHandle(request,new MockHttpServletResponse(),method("product")))
                .isInstanceOfSatisfying(BusinessException.class,ex -> assertThat(ex.getCode()).isEqualTo(403));
        verify(lookup,times(2)).permissions(1);
    }

    @Test void undeclaredBackOfficeEndpointIsDeniedAndDoesNotTrustMenuOrLocalKey() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/admin/new-feature"); request.setContextPath("/api");
        request.addHeader("X-Local-Admin-Key","synthetic");
        assertThatThrownBy(() -> new AdminInterceptor(null).preHandle(request,new MockHttpServletResponse(),method("publicLogin")))
                .isInstanceOfSatisfying(BusinessException.class,ex -> assertThat(ex.getCode()).isEqualTo(403));
    }
}
