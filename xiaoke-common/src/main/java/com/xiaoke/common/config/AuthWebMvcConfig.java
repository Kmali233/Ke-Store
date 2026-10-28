package com.xiaoke.common.config;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.LoginInterceptor;
import com.xiaoke.common.auth.LocalAdminInterceptor;
import com.xiaoke.common.auth.LocalAdminProperties;
import com.xiaoke.common.auth.admin.AdminAuthentication;
import com.xiaoke.common.auth.admin.AdminInterceptor;
import com.xiaoke.common.web.StrictInputPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册后台身份、本地管理门禁、买家身份与输入策略拦截器。
 *
 * 注册范围写 /**，绝不能写 /api —— context-path 已经吃掉了 /api，
 * 再写一次会变成 /api/api/...，拦截器永远不生效。
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    // 通过 ObjectProvider 而不是直接注入 JwtService，是为了支持未启用 JWT 的情况
    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final LocalAdminProperties localAdminProperties;
    private final Environment environment;
    private final ObjectProvider<AdminAuthentication> adminAuthentication;

    public AuthWebMvcConfig(ObjectProvider<JwtService> jwtServiceProvider,
                            LocalAdminProperties localAdminProperties, Environment environment,
                            ObjectProvider<AdminAuthentication> adminAuthentication) {
        this.jwtServiceProvider = jwtServiceProvider;
        this.localAdminProperties = localAdminProperties;
        this.environment = environment;
        this.adminAuthentication = adminAuthentication;
    }

    // 后台拦截器始终注册，认证组件缺失时对受保护方法拒绝访问。
    // 买家拦截器仍按原有JWT配置装配。
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        registry.addInterceptor(new AdminInterceptor(adminAuthentication.getIfAvailable()))
                .addPathPatterns("/**").order(-30);

        // 注册 LocalAdminInterceptor
        // 告诉 Spring：以后处理匹配路径的请求时，先经过这个拦截器:localAdminProperties, environment
        registry.addInterceptor(new LocalAdminInterceptor(localAdminProperties, environment))
                .addPathPatterns("/**")
                .order(-20);

        // 如果有 JwtService，则注册 LoginInterceptor
        // 告诉 Spring：以后处理匹配路径的请求时，先经过这个拦截器:jwtService
        if (jwtService != null) {
            registry.addInterceptor(new LoginInterceptor(jwtService))
                    .addPathPatterns("/**")  // 匹配所有路径
                    .order(-10);  // 拦截器顺序，越小越先执行
        }

        registry.addInterceptor(new StrictInputPolicy())
                .addPathPatterns("/**")
                .order(0);
    }
}
