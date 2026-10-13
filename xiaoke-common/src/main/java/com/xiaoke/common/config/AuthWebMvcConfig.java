package com.xiaoke.common.config;

import com.xiaoke.common.auth.JwtService;
import com.xiaoke.common.auth.LoginInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
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

    public AuthWebMvcConfig(ObjectProvider<JwtService> jwtServiceProvider) {
        this.jwtServiceProvider = jwtServiceProvider;
    }

    // 重点：注册拦截器，启动时判断当前服务是否启用 JWT；启用就注册一个拦截器去挂全路径，不启用就跳过
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        if (jwtService == null) {
            // 未启用 JWT 的服务（如 product）不需要买家身份，不注册拦截器
            return;
        }
        registry.addInterceptor(new LoginInterceptor(jwtService)).addPathPatterns("/**");
    }
}
