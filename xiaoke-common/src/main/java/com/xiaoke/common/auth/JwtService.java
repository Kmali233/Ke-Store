package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;


@Service
//只有 xiaoke.jwt.enabled=true 时，Spring 才创建这个服务。
@ConditionalOnProperty(prefix = "xiaoke.jwt", name = "enabled",
        havingValue = "true")
public class JwtService {
    public static final long EXPIRY_SECONDS = 7200;
    public static final String ISSUER = "xiaoke-mall";
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{4,50}");
    private final Clock clock;
    private final SecretKey key;

    // 构造方法接收JWT配置对象和时钟对象
    public JwtService(JwtProperties props, Clock clock) {
        this.clock = clock;
        String secret = props.getSecretBase64();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("xiaoke.jwt.secret-base64 is required");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT secret must be Base64");
        }
        if (raw.length < 32) {
            throw new IllegalStateException("JWT secret must contain at least 32 decoded bytes");
        }
        if (!ISSUER.equals(props.getIssuer()) || props.getExpirySeconds() != EXPIRY_SECONDS) {
            throw new IllegalStateException("JWT contract requires xiaoke-mall / 7200 seconds");
        }
        this.key = Keys.hmacShaKeyFor(raw);
    }

    // 根据用户ID和用户名生成JWT
    public String issue(Long userId, String username) {
        if (userId == null || userId <= 0 || username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid identity for token issuance");
        }
        Instant now = clock.instant();
        return Jwts.builder().issuer(ISSUER).subject(userId.toString())
                .claim("userId", userId).claim("username", username)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(EXPIRY_SECONDS)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    // 验证JWT并返回用户ID和用户名
    public AuthPrincipal verify(String rawJwt) {
        /** 空凭证直接失败 */
        if (rawJwt == null || rawJwt.isBlank()) {
            throw unauthorized();
        }
        /** 使用启动时准备好的 key */
        try {
            var jwt = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(rawJwt);
            /** 算法必须是项目要求的 HS256 */
            if (!"HS256".equals(jwt.getHeader().getAlgorithm())) {
                throw unauthorized();
            }
            /** 身份信息格式和对应关系必须合法 */
            Claims claims = jwt.getPayload();
            Object id = claims.get("userId");
            if (!(id instanceof Integer || id instanceof Long) || ((Number) id).longValue() <= 0) {
                throw unauthorized();
            }
            long userId = ((Number) id).longValue();
            Object name = claims.get("username");
            if (!(name instanceof String username) || !USERNAME.matcher(username).matches()
                    || !Long.toString(userId).equals(claims.getSubject())) {
                throw unauthorized();
            }
            /** 不能过期，有效期必须符合项目约定 */
            Date issued = claims.getIssuedAt();
            Date expires = claims.getExpiration();
            Instant now = clock.instant();
            if (issued == null || expires == null || issued.toInstant().isAfter(now)
                    || !now.isBefore(expires.toInstant())
                    || !issued.toInstant().plusSeconds(EXPIRY_SECONDS).equals(expires.toInstant())) {
                throw unauthorized();
            }
            /** 返回验证过的用户身份 */
            return new AuthPrincipal(userId, username);
        } catch (JwtException | IllegalArgumentException ex) {
            /** 交给外层统一处理，向外抛 */
            throw unauthorized();
        }
    }

    // 返回一个未授权的异常
    private static BusinessException unauthorized() {
        /** UNAUTHORIZED:401, "未认证"
         * 归类为 BusinessException 异常
         * */
        return new BusinessException(ResultCode.UNAUTHORIZED);
    }
}
