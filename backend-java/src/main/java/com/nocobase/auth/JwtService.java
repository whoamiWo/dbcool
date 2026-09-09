package com.nocobase.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发 / 解析服务.
 *
 * <p>实现 ADR-006:HS256 + 15 分钟 access + 7 天 refresh.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration accessTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl-minutes:15}") long accessTtlMinutes
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret 必须 ≥ 32 字节(256 位)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    /**
     * 签发 access token.
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param tenantId 租户 ID
     * @return 紧凑型 JWT 字符串
     */
    public String issueAccessToken(UUID userId, String username, String tenantId) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTtl);

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "username", username,
                        "tid", tenantId,
                        "typ", "access"
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析 access token.
     *
     * @param token JWT 字符串
     * @return 解析后的 Claims;若过期或非法返回 null
     */
    public Claims parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 必须是 access 类型
            Object typ = claims.get("typ");
            if (!"access".equals(typ)) {
                return null;
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }
}
