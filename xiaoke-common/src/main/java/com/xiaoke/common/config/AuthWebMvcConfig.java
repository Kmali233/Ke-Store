package com.xiaoke.common.config;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.LoginInterceptor;
import com.xiaoke.common.auth.LocalAdminInterceptor;
import com.xiaoke.common.auth.LocalAdminProperties;
import com.xiaoke.common.web.StrictInputPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册买家身份拦截器。
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

    public AuthWebMvcConfig(ObjectProvider<JwtService> jwtServiceProvider,
                            LocalAdminProperties localAdminProperties, Environment environment) {
        this.jwtServiceProvider = jwtServiceProvider;
        this.localAdminProperties = localAdminProperties;
        this.environment = environment;
    }

    // 重点：注册拦截器，启动时判断当前服务是否启用 JWT；
    // 启用就注册一个拦截器去挂全路径，不启用就跳过
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        JwtService jwtService = jwtServiceProvider.getIfAvailable();

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
