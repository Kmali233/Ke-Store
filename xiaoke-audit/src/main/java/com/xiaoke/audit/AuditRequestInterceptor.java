package com.xiaoke.audit;

import com.xiaoke.common.auth.admin.*;
import jakarta.servlet.http.*;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 请求结束后记录拒绝访问、敏感读取等安全日志 */
public class AuditRequestInterceptor implements HandlerInterceptor {
    private static final org.slf4j.Logger SECURITY=LoggerFactory.getLogger("xiaoke.security.audit");
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) { return true; }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception ex) {
        if(!(handler instanceof HandlerMethod method)) return;
        boolean login=method.getBeanType().getName().equals("com.xiaoke.admin.controller.AdminAuthController")
                && method.getMethod().getName().equals("login");
        if(!AdminInterceptor.requiresAdmin(method) && !login) return;
        int status=response.getStatus();
        boolean read="GET".equals(request.getMethod());
        if(status<400 && !read && !login && !method.getMethod().getName().equals("logout")) return;
        Object verified=request.getAttribute(AdminInterceptor.VERIFIED_PRINCIPAL);
        Long actor=verified instanceof AdminPrincipal p?p.adminId():null;
        var permission=method.getMethodAnnotation(AdminPermission.class);
        String operation=permission==null?(login?"LOGIN":"ADMIN_IDENTITY"):String.join("|",permission.value());
        String result=status>=400?"REJECTED":read?"READ_SUCCESS":"AUTH_SUCCESS";
        SECURITY.info("security_audit actorId={} operation={} result={} http={} traceId={}",actor,operation,result,status,
                response.getHeader("X-Trace-Id"));
    }
}
