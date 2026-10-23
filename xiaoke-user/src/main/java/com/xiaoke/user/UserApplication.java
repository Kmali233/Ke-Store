package com.xiaoke.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务入口：加载用户业务和 common 的通用组件。
 */
@SpringBootApplication(scanBasePackages = {"com.xiaoke.user", "com.xiaoke.common"})
@MapperScan("com.xiaoke.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
