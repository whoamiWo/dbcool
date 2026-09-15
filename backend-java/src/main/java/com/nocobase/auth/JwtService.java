package com.nocobase.auth;

import com.nocobase.auth.keystore.KeyRingEntry;
import com.nocobase.auth.keystore.KeyRingService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发 / 解析服务(Week 42 R10 — KMS 轮换接入).
 *
 * <p>实现 ADR-006 + R10 缓解:
 * <ul>
 *   <li>HS256 + 15 分钟 access TTL</li>
 *   <li>kid (Key ID) 写入 JWT header — 支持多密钥轮换</li>
 *   <li>签发用当前 ACTIVE key</li>
 *   <li>解析按 kid 查找;无 kid 时回退 ACTIVE</li>
 *   <li>密钥强度/轮换速率限制由 {@link KeyRingService} 负责</li>
 * </ul>
 */
@Service
public class JwtService {

    private final KeyRingService keyRing;
    private final Duration accessTtl;

    public JwtService(
            KeyRingService keyRing,
            @Value("${app.jwt.access-ttl-minutes:15}") long accessTtlMinutes
    ) {
        this.keyRing = keyRing;
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    /**
     * 签发 access token.
     *
     * @return 紧凑型 JWT(header 含 kid)
     * @throws IllegalStateException 当前无 ACTIVE key
     */
    public String issueAccessToken(UUID userId, String username, String tenantId) {
        Optional<KeyRingEntry> active = keyRing.currentActive();
        if (active.isEmpty()) {
            throw new IllegalStateException("无可用 ACTIVE 签名密钥");
        }
        KeyRingEntry entry = active.get();
        SecretKey signingKey = toHmacKey(entry.secret());

        Instant now = Instant.now();
        Instant exp = now.plus(accessTtl);

        return Jwts.builder()
                .header().keyId(entry.kid()).and()
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
     * 解析 access token.支持 kid 查找(轮换期间老 token 仍可用).
     *
     * @return Claims;若过期/非法/被撤销 返回 null
     */
    public Claims parseAccessToken(String token) {
        try {
            // 第一步:不验签解析 header 拿 kid
            String kid = extractKid(token);
            Optional<KeyRingEntry> entry = (kid != null)
                    ? keyRing.findByKid(kid)
                    : keyRing.fallbackActive();
            if (entry.isEmpty() || !entry.get().canVerify()) {
                return null;
            }
            SecretKey signingKey = toHmacKey(entry.get().secret());

            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object typ = claims.get("typ");
            if (!"access".equals(typ)) {
                return null;
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 token 头提取 kid — 不验签(我们只是要看 header 决定用哪个 key 验).
     */
    private String extractKid(String token) {
        try {
            int firstDot = token.indexOf('.');
            if (firstDot < 0) return null;
            String headerB64 = token.substring(0, firstDot);
            String headerJson = new String(
                    java.util.Base64.getUrlDecoder().decode(headerB64),
                    StandardCharsets.UTF_8);
            // 极简解析:找 "kid":"xxx"
            int kidIdx = headerJson.indexOf("\"kid\"");
            if (kidIdx < 0) return null;
            int colonIdx = headerJson.indexOf(':', kidIdx);
            int quoteStart = headerJson.indexOf('"', colonIdx + 1);
            int quoteEnd = headerJson.indexOf('"', quoteStart + 1);
            if (quoteStart < 0 || quoteEnd < 0) return null;
            return headerJson.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey toHmacKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes);
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }
}
