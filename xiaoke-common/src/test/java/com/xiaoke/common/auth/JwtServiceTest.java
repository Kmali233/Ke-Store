package com.xiaoke.common.auth;

import com.xiaoke.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET_A = b64("0123456789abcdef0123456789abcdef");
    private static final String SECRET_B = b64("fedcba9876543210fedcba9876543210");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private JwtProperties props(String secret) {
        JwtProperties p = new JwtProperties();
        p.setEnabled(true);
        p.setSecretBase64(secret);
        p.setIssuer("xiaoke-mall");
        p.setExpirySeconds(7200);
        return p;
    }

    private JwtService at(String secret, Instant instant) {
        return new JwtService(props(secret), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("J1 正常签发后验签，身份一致")
    void j1_issueThenVerify() {
        JwtService svc = at(SECRET_A, T0);
        AuthPrincipal p = svc.verify(svc.issue(42L, "kevin"));
        assertEquals(42L, p.userId());
        assertEquals("kevin", p.username());
    }

    @Test
    @DisplayName("J2/J3 过期边界：exp-1s 通过，exp+1s 401")
    void j2_j3_expiryBoundary() {
        String token = at(SECRET_A, T0).issue(1L, "kevin");   // exp = T0 + 7200s

        assertEquals(1L, at(SECRET_A, T0.plusSeconds(7199)).verify(token).userId());
        assertThrows(BusinessException.class,
                () -> at(SECRET_A, T0.plusSeconds(7201)).verify(token));
    }

    @Test
    @DisplayName("J4 声明完整性：sub / iss / exp-iat")
    void j4_claimsShape() {
        String token = at(SECRET_A, T0).issue(7L, "kevin");
        var claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_A)))
                .clock(() -> Date.from(T0))   // 原生 parser 也必须注入固定时钟，否则它拿真实系统时间判过期
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("7", claims.getSubject());
        assertEquals("xiaoke-mall", claims.getIssuer());
        assertEquals(7200L, (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000);
    }

    @Test
    @DisplayName("J5 换密钥签发的 token 一律 401")
    void j5_wrongKey() {
        String token = at(SECRET_A, T0).issue(1L, "kevin");
        assertThrows(BusinessException.class, () -> at(SECRET_B, T0).verify(token));
    }

    @Test
    @DisplayName("J6 issuer 不匹配 401")
    void j6_wrongIssuer() {
        JwtProperties other = props(SECRET_A);
        other.setIssuer("someone-else");
        String token = new JwtService(other, Clock.fixed(T0, ZoneOffset.UTC)).issue(1L, "kevin");
        assertThrows(BusinessException.class, () -> at(SECRET_A, T0).verify(token));
    }

    @Test
    @DisplayName("J7 userId 声明为字符串时 401（拒绝隐式强转）")
    void j7_userIdAsString() {
        String token = Jwts.builder()
                .issuer("xiaoke-mall")
                .subject("1")
                .claim("userId", "1")                 // 字符串，不是数字
                .claim("username", "kevin")
                .issuedAt(Date.from(T0))
                .expiration(Date.from(T0.plusSeconds(7200)))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_A)), Jwts.SIG.HS256)
                .compact();
        assertThrows(BusinessException.class, () -> at(SECRET_A, T0).verify(token));
    }

    @Test
    @DisplayName("J8 密钥非法时构造期即失败（fail-fast）")
    void j8_failFastOnBadSecret() {
        Clock c = Clock.fixed(T0, ZoneOffset.UTC);
        assertThrows(IllegalStateException.class, () -> new JwtService(props(null), c));
        assertThrows(IllegalStateException.class, () -> new JwtService(props("not-base64!!"), c));
        assertThrows(IllegalStateException.class, () -> new JwtService(props(b64("short")), c));
    }
}
