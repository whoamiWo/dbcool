package com.nocobase.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * JwtService 单测(Week 31).
 * 覆盖 issue + parse round-trip + 错误 token 返回 null + 短 secret 异常.
 */
class JwtServiceTest {

    private static final String VALID_SECRET = "this-is-a-32-byte-secret-key-for-hmac-sha256!!";

    @Test
    void constructor_shortSecret_throwsIllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("short", 15L));
        assertTrue(ex.getMessage().contains("≥ 32 字节"));
    }

    @Test
    void issueAccessToken_setsSubjectAndClaims() {
        JwtService svc = new JwtService(VALID_SECRET, 15L);
        UUID userId = UUID.randomUUID();
        String token = svc.issueAccessToken(userId, "alice", "tenant_default");
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3);  // JWT 三段
    }

    @Test
    void issueAndParse_roundTrip_returnsSameData() {
        JwtService svc = new JwtService(VALID_SECRET, 15L);
        UUID userId = UUID.randomUUID();
        String token = svc.issueAccessToken(userId, "alice", "tenant_default");
        Claims claims = svc.parseAccessToken(token);
        assertNotNull(claims);
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("alice", claims.get("username"));
        assertEquals("tenant_default", claims.get("tid"));
        assertEquals("access", claims.get("typ"));
    }

    @Test
    void parseAccessToken_invalidString_returnsNull() {
        JwtService svc = new JwtService(VALID_SECRET, 15L);
        assertNull(svc.parseAccessToken("not.a.jwt"));
        assertNull(svc.parseAccessToken("invalid"));
    }

    @Test
    void parseAccessToken_wrongSecret_returnsNull() {
        JwtService issuer = new JwtService(VALID_SECRET, 15L);
        JwtService verifier = new JwtService(
                "different-32-byte-secret-key-12345678!!", 15L);
        String token = issuer.issueAccessToken(UUID.randomUUID(), "u", "t");
        assertNull(verifier.parseAccessToken(token));
    }

    @Test
    void parseAccessToken_refreshTypeToken_returnsNull() {
        // 手动签发一个 typ=refresh 的 token,parse 应返回 null
        JwtService svc = new JwtService(VALID_SECRET, 15L);
        String token = io.jsonwebtoken.Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("typ", "refresh"))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        assertNull(svc.parseAccessToken(token));
    }

    @Test
    void getAccessTtl_returnsConfiguredDuration() {
        JwtService svc = new JwtService(VALID_SECRET, 30L);
        assertEquals(Duration.ofMinutes(30L), svc.getAccessTtl());
    }

    @Test
    void getAccessTtl_defaultUsed() {
        // Spring 默认值是 15 分钟(@Value default),直接传 15
        JwtService svc = new JwtService(VALID_SECRET, 15L);
        assertEquals(Duration.ofMinutes(15L), svc.getAccessTtl());
    }

    @Test
    void parseAccessToken_expiredToken_returnsNull() {
        JwtService svc = new JwtService(VALID_SECRET, 1L);  // 1 分钟,但我们签发已过期的
        UUID userId = UUID.randomUUID();
        // 直接构造已过期的 token
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 120_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        assertNull(svc.parseAccessToken(expiredToken));
    }
}
