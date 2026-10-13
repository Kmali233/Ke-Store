package com.xiaoke.common.config;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.LoginRequired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期一致性校验。
 *
 * 声明了 @LoginRequired 却没启用 JWT，说明漏配了 xiaoke.jwt.enabled / secret-base64。
 * 放任启动的话，拦截器不会注册、身份永远取不到，那些接口会在运行时稳定返回 401，
 * 排查成本远高于启动时直接报错。
 */
@Component
public class AuthStartupValidator implements SmartInitializingSingleton {

    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final ObjectProvider<RequestMappingHandlerMapping> mappingProvider;

    public AuthStartupValidator(ObjectProvider<JwtService> jwtServiceProvider,
                                ObjectProvider<RequestMappingHandlerMapping> mappingProvider) {
        this.jwtServiceProvider = jwtServiceProvider;
        this.mappingProvider = mappingProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 此刻所有单例都已实例化，RequestMappingHandlerMapping 的映射表也已构建完成
        boolean jwtEnabled = jwtServiceProvider.getIfAvailable() != null;
        RequestMappingHandlerMapping mapping = mappingProvider.getIfAvailable();
        if (mapping == null) {
            return;   // 非 Web 场景，无需校验
        }
        validate(jwtEnabled, mapping.getHandlerMethods().values());
    }

    /**
     * 判定逻辑抽成纯函数：不依赖 Spring 上下文，可以直接单测三个分支。
     */
    static void validate(boolean jwtEnabled, Iterable<HandlerMethod> handlers) {
        if (jwtEnabled) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : handlers) {
            if (AnnotatedElementUtils.hasAnnotation(handler.getMethod(), LoginRequired.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), LoginRequired.class)) {
                violations.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "发现声明了 @LoginRequired 但未启用 JWT 的接口：" + violations
                            + "。请配置 xiaoke.jwt.enabled=true 与 xiaoke.jwt.secret-base64。");
        }
    }
}
