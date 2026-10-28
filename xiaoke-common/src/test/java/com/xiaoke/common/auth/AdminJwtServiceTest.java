package com.xiaoke.common.auth;

import com.xiaoke.common.auth.admin.*;
import com.xiaoke.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AdminJwtServiceTest {
    private final Instant now = Instant.parse("2026-10-28T04:00:00Z");
    private final String secret = Base64.getEncoder().encodeToString(new byte[32]);
    private final AdminPrincipal principal = new AdminPrincipal(1, "ops_admin", 3);

    private AdminJwtService service(Instant time) {
        var props = new AdminProperties(); props.setSecretBase64(secret);
        return new AdminJwtService(props, new JwtProperties(), Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test void validUntilExactExpiryAndCannotBeUsedByBuyer() {
        String token = service(now).issue(principal);
        assertThat(service(now.plusSeconds(1799)).verify(token)).isEqualTo(principal);
        assertThatThrownBy(() -> service(now.plusSeconds(1800)).verify(token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service(now.minusSeconds(1)).verify(token)).isInstanceOf(BusinessException.class);
        var buyer = new JwtProperties(); buyer.setSecretBase64(secret);
        var jwt = new JwtService(buyer, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service(now).verify(jwt.issue(1L, "ops_admin"))).isInstanceOf(BusinessException.class);
    }

    @Test void rejectsMissingClaimsWrongPurposeAlgorithmSignatureAndMalformedIdentity() {
        for (String variant : List.of("purpose", "issuer", "audience", "subject", "version", "username", "expiry", "id", "algorithm", "key")) {
            var b = Jwts.builder().issuer(variant.equals("issuer") ? "xiaoke-mall" : AdminJwtService.ISSUER)
                    .audience().add(variant.equals("audience") ? "buyers" : AdminJwtService.AUDIENCE).and()
                    .subject(variant.equals("subject") ? "0" : "1")
                    .claim("purpose", variant.equals("purpose") ? "buyer" : "admin")
                    .claim("username", variant.equals("username") ? "Invalid Name" : "ops_admin")
                    .claim("tokenVersion", variant.equals("version") ? "3" : 3)
                    .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(variant.equals("expiry") ? 3600 : 1800)));
            if (!variant.equals("id")) { b.id(UUID.randomUUID().toString()); }
            byte[] raw = new byte[variant.equals("algorithm") ? 64 : 32];
            if (variant.equals("key")) { Arrays.fill(raw, (byte) 1); }
            String token = b.signWith(Keys.hmacShaKeyFor(raw), variant.equals("algorithm") ? Jwts.SIG.HS512 : Jwts.SIG.HS256).compact();
            assertThatThrownBy(() -> service(now).verify(token)).as(variant).isInstanceOf(BusinessException.class);
        }
        for (String token : List.of("", "Bearer malformed", "x".repeat(4097))) {
            assertThatThrownBy(() -> service(now).verify(token)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service(now).verify(null)).isInstanceOf(BusinessException.class);
    }

    @Test void missingWeakMalformedOrSharedKeyRefusesStartup() {
        for (String value : Arrays.asList(null, "", "%%%", Base64.getEncoder().encodeToString(new byte[16]))) {
            var props = new AdminProperties(); props.setSecretBase64(value);
            assertThatThrownBy(() -> new AdminJwtService(props, new JwtProperties(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }
        var props = new AdminProperties(); props.setSecretBase64(secret);
        var buyer = new JwtProperties(); buyer.setSecretBase64(secret);
        assertThatThrownBy(() -> new AdminJwtService(props, buyer, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must differ");
    }
}
