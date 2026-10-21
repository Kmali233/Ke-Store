package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class LocalAdminInterceptor implements HandlerInterceptor {
    private final LocalAdminProperties properties;
    private final Environment environment;

    public LocalAdminInterceptor(LocalAdminProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !isAdmin(method)) {
            return true;
        }
        var profiles = Arrays.asList(environment.getActiveProfiles());
        String secret = properties.getLocalAdminKey();
        if (!profiles.contains("dev") || profiles.contains("prod") || !properties.isLocalAdminEnabled()
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"127.0.0.1".equals(request.getRemoteAddr()) || secret == null || secret.isBlank()) {
            throw new BusinessException(403, "本地管理接口未开放");
        }
        String supplied = request.getHeader("X-Local-Admin-Key");
        if (supplied == null || !MessageDigest.isEqual(digest(secret), digest(supplied))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return true;
    }

    public static boolean isAdmin(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), LocalAdminOnly.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), LocalAdminOnly.class);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行时缺少 SHA-256");
        }
    }
}
