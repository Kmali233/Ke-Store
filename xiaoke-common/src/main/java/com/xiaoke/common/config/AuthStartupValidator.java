package com.xiaoke.common.config;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.LocalAdminInterceptor;
import com.xiaoke.common.auth.admin.AdminInterceptor;
import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期一致性校验,自动检查认证配置是否遗漏或冲突
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
        mapping.getHandlerMethods().forEach((info, handler) -> {
            boolean adminPath = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/admin/"));
            boolean write = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods().stream().anyMatch(m -> m != RequestMethod.GET
                        && m != RequestMethod.HEAD && m != RequestMethod.OPTIONS);
            boolean authEndpoint = handler.getBeanType().getName().equals("com.xiaoke.admin.controller.AdminAuthController")
                    && info.getPatternValues().size()==1
                    && info.getPatternValues().stream().allMatch(p -> p.equals("/admin/auth/login") || p.equals("/admin/auth/logout"))
                    && info.getMethodsCondition().getMethods().equals(java.util.Set.of(RequestMethod.POST));
            if ((adminPath || AdminInterceptor.requiresAdmin(handler)) && write && !authEndpoint
                    && !handler.hasMethodAnnotation(AdminPermission.class)) {
                throw new IllegalStateException("Back-office writes require AdminPermission: " + handler.getMethod().getName());
            }
            boolean catalogWrite = info.getPatternValues().stream().anyMatch(p -> p.equals("/product")
                    || p.startsWith("/product/") || p.equals("/category") || p.startsWith("/category/")
                    || p.equals("/order/{orderNo}/ship"));
            if (catalogWrite && write && !handler.hasMethodAnnotation(AdminPermission.class)) {
                throw new IllegalStateException("Management writes require AdminPermission: " + handler.getMethod().getName());
            }
        });
    }

    /**
     * 判定逻辑抽成纯函数：不依赖 Spring 上下文，可以直接单测三个分支。
     */
    static void validate(boolean jwtEnabled, Iterable<HandlerMethod> handlers) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : handlers) {
            var permission = handler.getMethodAnnotation(AdminPermission.class);
            if (permission != null && (permission.value().length == 0 || !AdminPermissions.ALL.containsAll(java.util.Arrays.asList(permission.value())))) {
                throw new IllegalStateException("Unknown administrator permission");
            }
            boolean login = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), LoginRequired.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), LoginRequired.class);
            if (AdminInterceptor.requiresAdmin(handler)
                    && (login || LocalAdminInterceptor.isAdmin(handler))) {
                throw new IllegalStateException("AdminRequired cannot be combined with buyer/local admin guards");
            }
            if (login && LocalAdminInterceptor.isAdmin(handler)) {
                throw new IllegalStateException("接口不能同时声明 LoginRequired 与 LocalAdminOnly: "
                        + handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
            }
            if (login && !jwtEnabled) {
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
