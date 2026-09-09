package com.nocobase.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test_secret_at_least_32_characters_long_for_hs256";

    private JwtService newService() {
        return new JwtService(SECRET, 15);
    }

    @Test
    void issue_and_parse_token_roundtrip() {
        JwtService svc = newService();
        UUID userId = UUID.randomUUID();
        String token = svc.issueAccessToken(userId, "alice", "tenant_a");

        assertNotNull(token);
        Claims claims = svc.parseAccessToken(token);
        assertNotNull(claims);
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("alice", claims.get("username"));
        assertEquals("tenant_a", claims.get("tid"));
        assertEquals("access", claims.get("typ"));
    }

    @Test
    void invalid_token_returns_null() {
        JwtService svc = newService();
        assertNull(svc.parseAccessToken("not.a.valid.jwt"));
        assertNull(svc.parseAccessToken(""));
    }

    @Test
    void token_signed_with_different_secret_returns_null() {
        JwtService issuer = new JwtService("another_secret_at_least_32_characters_long_xxx", 15);
        String token = issuer.issueAccessToken(UUID.randomUUID(), "bob", "t1");

        JwtService verifier = newService();
        assertNull(verifier.parseAccessToken(token));
    }

    @Test
    void short_secret_throws_at_construction() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService("short", 15));
    }
}
