package com.xiaoke.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 跨域配置
 * 允许哪些前端来源。
 * 允许哪些请求方法。
 * 允许携带哪些请求头，例如 Authorization。
 * 允许前端读取哪些响应头，例如 X-Trace-Id。
 */
@Configuration    // 配置类，用来声明 Bean 或配置框架
public class CorsConfig implements WebMvcConfigurer {
    private final CommonWebProperties properties;

    public CorsConfig(CommonWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Local-Admin-Key", "Idempotency-Key")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Bean   // 把这个方法返回的对象交给 Spring 管理
    public static BeanPostProcessor corsErrorResponse() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof AbstractHandlerMapping mapping) {
                    mapping.setCorsProcessor(new DefaultCorsProcessor() {
                        @Override
                        protected void rejectRequest(ServerHttpResponse response) throws IOException {
                            response.setStatusCode(HttpStatus.FORBIDDEN);
                            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            response.getBody().write(
                                    "{\"code\":403,\"message\":\"跨域请求未允许\",\"data\":null}".getBytes(StandardCharsets.UTF_8));
                            response.flush();
                        }
                    });
                }
                return bean;
            }
        };
    }
}
