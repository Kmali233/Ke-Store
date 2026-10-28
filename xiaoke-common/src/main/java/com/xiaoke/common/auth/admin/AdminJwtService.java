package com.xiaoke.common.auth.admin;

import com.xiaoke.common.auth.JwtProperties;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * 签发、验证后台 JWT
 */
@Service
@ConditionalOnProperty(prefix = "xiaoke.admin", name = "enabled", havingValue = "true")
public class AdminJwtService {
    public static final String ISSUER = "xiaoke-admin";
    public static final String AUDIENCE = "xiaoke-backoffice";
    public static final long EXPIRY_SECONDS = 1800;
    private final SecretKey key;
    private final Clock clock;

    public AdminJwtService(AdminProperties props, JwtProperties buyer, Clock clock) {
        this.clock = clock;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(props.getSecretBase64());
            if (raw.length < 32) { throw new IllegalArgumentException(); }
            // Also compare on product, where buyer JWT issuance may be disabled.
            if (buyer.getSecretBase64() != null && !buyer.getSecretBase64().isBlank()
                    && MessageDigest.isEqual(raw, Base64.getDecoder().decode(buyer.getSecretBase64()))) {
                throw new IllegalStateException("Administrator and buyer JWT keys must differ");
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("xiaoke.admin.secret-base64 requires at least 32 decoded bytes");
        }
        key = Keys.hmacShaKeyFor(raw);
    }

    public String issue(AdminPrincipal principal) {
        if (principal.adminId() <= 0 || principal.tokenVersion() < 0
                || principal.username() == null || !principal.username().matches("[a-z0-9_]{4,50}")) {
            throw new IllegalArgumentException("Invalid administrator identity");
        }
        var now = clock.instant();
        return Jwts.builder().issuer(ISSUER).audience().add(AUDIENCE).and()
                .subject(Long.toString(principal.adminId())).claim("purpose", "admin")
                .claim("username", principal.username()).claim("tokenVersion", principal.tokenVersion())
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(EXPIRY_SECONDS)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public AdminPrincipal verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) { throw unauthorized(); }
        try {
            var signed = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                    .require("purpose", "admin").clock(() -> Date.from(clock.instant()))
                    .build().parseSignedClaims(token);
            var c = signed.getPayload();
            long id = Long.parseLong(c.getSubject());
            Object version = c.get("tokenVersion");
            Object name = c.get("username");
            var now = clock.instant();
            if (!"HS256".equals(signed.getHeader().getAlgorithm()) || id <= 0
                    || !Long.toString(id).equals(c.getSubject())
                    || !(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() < 0
                    || !(name instanceof String username) || !username.matches("[a-z0-9_]{4,50}")
                    || c.getId() == null || c.getId().isBlank()
                    || c.getIssuedAt() == null || c.getExpiration() == null
                    || c.getIssuedAt().toInstant().isAfter(now) || !now.isBefore(c.getExpiration().toInstant())
                    || !c.getIssuedAt().toInstant().plusSeconds(EXPIRY_SECONDS).equals(c.getExpiration().toInstant())) {
                throw unauthorized();
            }
            return new AdminPrincipal(id, (String) name, ((Number) version).longValue());
        } catch (JwtException | IllegalArgumentException ex) {
            throw unauthorized();
        }
    }

    private static BusinessException unauthorized() { return new BusinessException(ResultCode.UNAUTHORIZED); }
}
