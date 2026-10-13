package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 买家身份拦截器。
 *
 * 只做两件事：验证 Authorization 头里的 JWT、把身份放进 UserContext。
 * 不做业务授权（"这个订单是不是你的"属于业务层）。
 *
 * 故意不标 @Component：是否装配由 AuthWebMvcConfig 按 xiaoke.jwt.enabled 决定。
 */
public class LoginInterceptor implements HandlerInterceptor {

    /** 契约：Authorization 里放原始 JWT，没有 Bearer 前缀 */
    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final JwtService jwtService;

    public LoginInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 预处理：验证 JWT 并把身份放进 UserContext。
     * @return 如果返回 false，请求将被中断
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!requiresLogin(handler)) {
            // 公开接口连 Authorization 都不读：带一个过期 token 也不该被阻断
            return true;
        }
        String raw = request.getHeader(HEADER_AUTHORIZATION);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        try {
            UserContext.set(jwtService.verify(raw));
        } catch (BusinessException e) {
            // 认证失败立即清空，不留半初始化状态
            UserContext.clear();
            throw e;
        }
        return true;
    }

    /**
     * 完成请求处理后执行，主要用于清理工作。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 补充清理。preHandle 返回 false 或直接抛异常时不会走到这里，
        // 所以还需要最外层过滤器兜底（Step 1.3b）——这是有意为之的冗余。
        UserContext.clear();
    }

    /** 类上有注解、方法上没有，仍然受保护 */
    private boolean requiresLogin(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), LoginRequired.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), LoginRequired.class);
    }
}
