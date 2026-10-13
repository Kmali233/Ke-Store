package com.xiaoke.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源。抽成 Bean 是为了让 JWT 的签发/校验可用固定时钟测试，
 * 不必靠 Thread.sleep 等待真实时间流逝。
 */
@Configuration   // 这个类不写业务逻辑，专门用来往容器里注册 Bean
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
