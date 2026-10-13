package com.xiaoke.common.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置，属性路径固定为 xiaoke.jwt.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaoke.jwt")
public class JwtProperties {

    /** 是否启用 JWT。默认 false：product 不配这块也能正常启动 */
    private boolean enabled = false;

    /** Base64 编码的 HMAC 密钥，解码后至少 32 字节。刻意不提供代码内默认值 */
    private String secretBase64;

    /** JWT 的发行者。默认 "xiaoke-mall" */
    private String issuer = "xiaoke-mall";

    /** JWT 的过期时间，单位秒。默认 7200（2 小时） */
    private long expirySeconds = 7200;
}
