package com.xiaoke.common.auth.admin;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 拦截请求、执行认证
 */
public class AdminInterceptor implements HandlerInterceptor {
    public static final String VERIFIED_PRINCIPAL = AdminInterceptor.class.getName()+".verifiedPrincipal";
    private final AdminAuthentication authentication;
    private final AdminPermissionLookup permissions;
    public AdminInterceptor(AdminAuthentication authentication) {
        this(authentication, null);
    }
    public AdminInterceptor(AdminAuthentication authentication, AdminPermissionLookup permissions) {
        this.authentication = authentication;
        this.permissions = permissions;
    }
    public static boolean requiresAdmin(HandlerMethod handler) {
        return handler.hasMethodAnnotation(AdminPermission.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getMethod(), AdminRequired.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), AdminRequired.class);
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminContext.clear();
        request.removeAttribute(VERIFIED_PRINCIPAL);
        if (request.getRequestURI().startsWith(request.getContextPath() + "/admin/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (handler instanceof HandlerMethod method && requiresAdmin(method)) {
            // Fail closed even if someone imports an annotated endpoint without enabling admin auth.
            if (authentication == null) { throw new BusinessException(ResultCode.UNAUTHORIZED); }
            AdminContext.set(authentication.authenticate(request.getHeader("Authorization")));
            request.setAttribute(VERIFIED_PRINCIPAL, AdminContext.requirePrincipal());
            var required = method.getMethodAnnotation(AdminPermission.class);
            if (required != null && (permissions == null || !AdminPermissions.ALL.contains(required.value())
                    || !permissions.permissions(AdminContext.requirePrincipal().adminId()).contains(required.value()))) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
        } else if (handler instanceof HandlerMethod
                && request.getRequestURI().startsWith(request.getContextPath() + "/admin/")
                && !("POST".equals(request.getMethod())
                    && request.getRequestURI().equals(request.getContextPath() + "/admin/auth/login"))) {
            // New back-office controllers cannot accidentally become public.
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminContext.clear();
    }
}
