package com.xiaoke.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希配置：后续注册和登录业务通过构造方法注入 PasswordEncoder。
 */
@Configuration
public class PasswordConfig {

    /** BCrypt 自动生成随机盐，10 是计算成本参数。
     *
     * $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
     *  └┬┘ └┬┘└──────────┬──────────┘└────────────┬─────────────┘
     *   │   │            │                        │
     *   │   │            └ 22 字符 = 盐            └ 31 字符 = 密文
     *   │   └ 成本参数 10
     *   └ 版本号 2a
     * */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
