package com.nocobase.auth.keystore;

import java.time.Instant;

/**
 * 单个 JWT 签名密钥(Week 42 R10 — KMS 轮换基础).
 *
 * <p>每个 entry 含:
 * <ul>
 *   <li>{@code kid}:公开 key id(放入 JWT header 让客户端识别)</li>
 *   <li>{@code secret}:原始 secret(从不暴露)</li>
 *   <li>{@code status}:ACTIVE(签发+验证) / RETIRED(只验证,不签发) / REVOKED(拒绝)</li>
 *   <li>{@code createdAt} / {@code retiredAt} / {@code revokedAt}:审计时间戳</li>
 * </ul>
 */
public record KeyRingEntry(
        String kid,
        String secret,
        Status status,
        Instant createdAt,
        Instant retiredAt,
        Instant revokedAt
) {
    public enum Status { ACTIVE, RETIRED, REVOKED }

    /** 简化构造:新创建的 ACTIVE key。 */
    public static KeyRingEntry active(String kid, String secret) {
        return new KeyRingEntry(kid, secret, Status.ACTIVE,
                Instant.now(), null, null);
    }

    /** 是否可用于签发新 token — 仅 ACTIVE。 */
    public boolean canSign() {
        return status == Status.ACTIVE;
    }

    /** 是否可用于验证 — ACTIVE 或 RETIRED。 */
    public boolean canVerify() {
        return status == Status.ACTIVE || status == Status.RETIRED;
    }
}
