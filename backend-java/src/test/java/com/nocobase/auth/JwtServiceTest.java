package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nocobase.auth.keystore.KeyRingService;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * JwtService 单测(Week 42 R10 重构 — 接入 KeyRingService).
 *
 * <p>覆盖 issue + parse round-trip + 错误 token 返回 null + kid 轮换兼容。
 */
class JwtServiceTest {

    private static final String VALID_SECRET = "this-is-a-32-byte-secret-key-for-hmac-sha256!!";

    private JwtService newService() {
        return newServiceWithSecret(VALID_SECRET);
    }

    private static org.springframework.core.env.Environment mockEnv() {
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        return env;
    }

    private JwtService newServiceWithSecret(String secret) {
        KeyRingService keyRing = new KeyRingService(secret, "", mockEnv());
        return new JwtService(keyRing, 15L);
    }

    private JwtService newServiceWithRing(KeyRingService keyRing) {
        return new JwtService(keyRing, 15L);
    }

    @Test
    void issueAccessToken_writesKidInHeader() {
        JwtService svc = newService();
        String token = svc.issueAccessToken(UUID.randomUUID(), "alice", "tenant_default");
        // token 第一段(header) base64url 解码后含 kid
        String[] parts = token.split("\\.");
        String headerJson = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(headerJson.contains("\"kid\":\"k-0\""));
    }

    @Test
    void issueAndParse_roundTrip_returnsSameData() {
        JwtService svc = newService();
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
        JwtService svc = newService();
        assertNull(svc.parseAccessToken("not.a.jwt"));
        assertNull(svc.parseAccessToken("invalid"));
    }

    @Test
    void parseAccessToken_differentSecret_returnsNull() {
        JwtService issuer = newService();
        JwtService verifier = newServiceWithSecret("different-32-byte-secret-key-12345678!!");
        String token = issuer.issueAccessToken(UUID.randomUUID(), "u", "t");
        assertNull(verifier.parseAccessToken(token));
    }

    @Test
    void parseAccessToken_refreshTypeToken_returnsNull() {
        // 手动签发一个 typ=refresh 的 token,parse 应返回 null
        JwtService svc = newService();
        String token = Jwts.builder()
                .header().keyId("k-0").and()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("typ", "refresh"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        assertNull(svc.parseAccessToken(token));
    }

    @Test
    void parseAccessToken_noKidHeader_fallsBackToActive() {
        // 老 token 没带 kid header,服务回退到当前 active — 兼容 G1 token
        JwtService svc = newService();
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        Claims claims = svc.parseAccessToken(token);
        assertNotNull(claims);
    }

    @Test
    void rotation_oldKidStillVerifies() {
        // 服务 A 签发,kid=k-0
        KeyRingService keyRing = new KeyRingService(VALID_SECRET, "", mockEnv());
        JwtService svcA = new JwtService(keyRing, 15L);
        UUID userId = UUID.randomUUID();
        String token = svcA.issueAccessToken(userId, "alice", "tenant_default");
        // header 应含 kid=k-0
        String headerJson = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[0]));
        assertTrue(headerJson.contains("\"kid\":\"k-0\""));

        // 轮换 — 生成 kid=k-1,k-0 变 RETIRED
        try {
            java.lang.reflect.Field f = KeyRingService.class.getDeclaredField("lastRotationAt");
            f.setAccessible(true);
            f.setLong(keyRing, 0);
        } catch (Exception ignore) {}
        keyRing.rotate();

        // 验证老 token 仍可解析
        Claims claims = svcA.parseAccessToken(token);
        assertNotNull(claims);
        assertEquals(userId.toString(), claims.getSubject());

        // 新签发的 token 应有 kid=k-1
        String token2 = svcA.issueAccessToken(UUID.randomUUID(), "b", "t");
        String headerJson2 = new String(java.util.Base64.getUrlDecoder()
                .decode(token2.split("\\.")[0]));
        assertTrue(headerJson2.contains("\"kid\":\"k-1\""));
    }

    @Test
    void rotation_revokedKid_returnsNull() {
        // 服务 A 签发 + 轮换 + 撤销 k-0
        KeyRingService keyRing = new KeyRingService(VALID_SECRET, "", mockEnv());
        JwtService svc = new JwtService(keyRing, 15L);
        String token = svc.issueAccessToken(UUID.randomUUID(), "u", "t");

        // 绕过速率限 rotate
        try {
            java.lang.reflect.Field f = KeyRingService.class.getDeclaredField("lastRotationAt");
            f.setAccessible(true);
            f.setLong(keyRing, 0);
        } catch (Exception ignore) {}
        keyRing.rotate();
        keyRing.revoke("k-0");

        // 撤销后老 token 不可解析
        assertNull(svc.parseAccessToken(token));
    }

    @Test
    void getAccessTtl_returnsConfiguredDuration() {
        JwtService svc = new JwtService(new KeyRingService(VALID_SECRET, "", mockEnv()), 30L);
        assertEquals(Duration.ofMinutes(30L), svc.getAccessTtl());
    }

    @Test
    void getAccessTtl_default15Min() {
        JwtService svc = newService();
        assertEquals(Duration.ofMinutes(15L), svc.getAccessTtl());
    }

    @Test
    void parseAccessToken_expiredToken_returnsNull() {
        JwtService svc = newService();
        String expiredToken = Jwts.builder()
                .header().keyId("k-0").and()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        assertNull(svc.parseAccessToken(expiredToken));
    }

    @Test
    void issue_whenNoActive_throws() {
        // 用反射清空 keyRing entries,然后 issue
        KeyRingService keyRing = new KeyRingService(VALID_SECRET, "", mockEnv());
        try {
            java.lang.reflect.Field f = KeyRingService.class.getDeclaredField("entries");
            f.setAccessible(true);
            ((java.util.Map<?, ?>) f.get(keyRing)).clear();
        } catch (Exception ignore) {}
        JwtService svc = new JwtService(keyRing, 15L);
        try {
            svc.issueAccessToken(UUID.randomUUID(), "u", "t");
            assertTrue(false, "应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("ACTIVE"));
        }
    }

    @Test
    void parse_unknownKid_returnsNull() {
        JwtService svc = newService();
        // 手动签发一个携带陌生 kid 的 token
        String token = Jwts.builder()
                .header().keyId("k-ghost").and()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes()))
                .compact();
        assertNull(svc.parseAccessToken(token));
    }

    @Test
    void previousSecret_tokenStillVerifies() {
        // G1 兼容:启动时 previous-secret 加载为 RETIRED,签发的 token 用 active
        // 但若进程切换 secret(previous=新),旧 token 仍可验证
        String oldSecret = "previous-secret-from-old-deployment-1234567890!";
        String newSecret = "this-is-a-new-different-32-byte-secret-key-abcdef";
        KeyRingService ring = new KeyRingService(newSecret, oldSecret, mockEnv());
        JwtService svc = new JwtService(ring, 15L);

        // 模拟老进程签发的 token(用 oldSecret,无 kid — 走 fallback)
        String oldToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(oldSecret.getBytes()))
                .compact();
        // 老 token 无 kid → fallback ACTIVE(=newSecret) → 解析失败
        assertNull(svc.parseAccessToken(oldToken));

        // 手动签发一个带 kid=k-prev 用 oldSecret 的 token
        String oldTokenWithKid = Jwts.builder()
                .header().keyId("k-prev").and()
                .subject(UUID.randomUUID().toString())
                .claims(java.util.Map.of("username", "u", "tid", "t", "typ", "access"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(oldSecret.getBytes()))
                .compact();
        Claims claims = svc.parseAccessToken(oldTokenWithKid);
        assertNotNull(claims);
    }
}
