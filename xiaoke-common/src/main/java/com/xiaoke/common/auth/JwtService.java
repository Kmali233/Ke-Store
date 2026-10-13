package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "xiaoke.jwt", name = "enabled", havingValue = "true")
public class JwtService {
    // JWT 的两个自定义 Claims 名称
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USERNAME = "username";

    private final JwtProperties props; // JWT 配置项
    private final Clock clock;  // 时钟
    private final SecretKey key;  // 签名密钥

    public JwtService(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = buildKey(props);
    }

    /** 启动期 fail-fast：密钥缺失/非法/过短一律拒绝启动，绝不使用代码内默认密钥 */
    private static SecretKey buildKey(JwtProperties props) {
        String secret = props.getSecretBase64();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("xiaoke.jwt.secret-base64 未配置，拒绝启动");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("xiaoke.jwt.secret-base64 不是合法 Base64，拒绝启动", e);
        }
        if (raw.length < 32) {
            throw new IllegalStateException("JWT 密钥解码后不足 32 字节，拒绝启动");
        }
        if (props.getIssuer() == null || props.getIssuer().isBlank()) {
            throw new IllegalStateException("xiaoke.jwt.issuer 未配置，拒绝启动");
        }
        if (props.getExpirySeconds() <= 0) {
            throw new IllegalStateException("xiaoke.jwt.expiry-seconds 必须为正数，拒绝启动");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /** 签发。只做签名，绝不查用户表 */
    public String issue(Long userId, String username) {
        long now = clock.millis();
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getExpirySeconds() * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 验签。任何一项不满足都抛 401，不向外泄漏具体原因 */
    public AuthPrincipal verify(String rawJwt) {
        if (rawJwt == null || rawJwt.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)                              // 只接受 HS256 签名；alg=none 在此被拒
                    .requireIssuer(props.getIssuer())
                    .clock(() -> Date.from(clock.instant()))      // 时钟可注入，测试不靠 sleep
                    .build()
                    .parseSignedClaims(rawJwt)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        Long userId = readUserId(claims);
        String username = claims.get(CLAIM_USERNAME, String.class);
        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!String.valueOf(userId).equals(claims.getSubject())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return new AuthPrincipal(userId, username);
    }

    /** userId 必须是正整数；字符串形式的 "1" 一律拒绝，防隐式强转 */
    private Long readUserId(Claims claims) {
        Object raw = claims.get(CLAIM_USER_ID);
        if (raw instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }
}
