package com.xiaoke.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.*;
import java.time.Clock;

/** 创建 Writer、Reader，并注册审计拦截器 */
@Configuration(proxyBeanMethods=false)  // 禁用代理
public class AuditConfiguration implements WebMvcConfigurer {
    @Bean public AuditWriter auditWriter(JdbcTemplate jdbc,ObjectMapper json,Clock clock,Environment env) {
        return new AuditWriter(jdbc,json,clock,env);
    }
    @Bean public AuditReader auditReader(JdbcTemplate jdbc,ObjectMapper json,Clock clock,jakarta.validation.Validator validator) {
        return new AuditReader(jdbc,json,clock,validator);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        // Runs before authentication so denied requests also receive afterCompletion.
        registry.addInterceptor(new AuditRequestInterceptor()).addPathPatterns("/**").order(-40);
    }
}
