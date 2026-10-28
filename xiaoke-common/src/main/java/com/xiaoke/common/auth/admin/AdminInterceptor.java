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
    private final AdminAuthentication authentication;
    public AdminInterceptor(AdminAuthentication authentication) {
        this.authentication = authentication;
    }
    public static boolean requiresAdmin(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), AdminRequired.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), AdminRequired.class);
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminContext.clear();
        if (request.getRequestURI().startsWith(request.getContextPath() + "/admin/auth/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (handler instanceof HandlerMethod method && requiresAdmin(method)) {
            // Fail closed even if someone imports an annotated endpoint without enabling admin auth.
            if (authentication == null) { throw new BusinessException(ResultCode.UNAUTHORIZED); }
            AdminContext.set(authentication.authenticate(request.getHeader("Authorization")));
        }
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminContext.clear();
    }
}
