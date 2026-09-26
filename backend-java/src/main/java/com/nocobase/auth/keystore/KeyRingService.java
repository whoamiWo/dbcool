package com.nocobase.auth.keystore;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT Key 轮换 (R10 — Week 42).
 *
 * <p>管理 1+ 个 JWT 签名密钥,支持运行时轮换:
 * <ol>
 *   <li><strong>启动</strong>:读 {@code app.jwt.secret} 作为初始 active key (kid=k-0)
 *       + 可选 {@code app.jwt.previous-secret} 作为 retired key</li>
 *   <li><strong>轮换</strong>:生成新密钥 (kid=k-N),旧 active → retired</li>
 *   <li><strong>撤销</strong>:任何 key 可设为 REVOKED(签名/验证都拒)</li>
 *   <li><strong>解析</strong>:根据 JWT header 中的 kid 找 entry;无 kid 头时尝试 active</li>
 * </ol>
 *
 * <p><strong>安全约束</strong>:
 * <ul>
 *   <li>secret 永不出现在日志/响应</li>
 *   <li>轮换速率限制 (1/min)— 防误操作把全部密钥废掉</li>
 *   <li>至少保留 1 个 ACTIVE key(rotate 时强制)</li>
 *   <li>轮换不持久化(进程重启从 yml 重读)— 适合 MVP,KMS 集成是 Week 43+</li>
 * </ul>
 */
@Service
public class KeyRingService {

    private static final Logger log = LoggerFactory.getLogger(KeyRingService.class);

    /** 默认 secret 强度警告阈值。生产必须 ≥ 32 字节。 */
    public static final int MIN_SECRET_BYTES = 32;

    /** 轮换速率限制:同进程内两次轮换至少间隔 60s。 */
    public static final long ROTATION_MIN_INTERVAL_MS = 60_000;

    /** 新生成 secret 的随机字节数 = 64 → 64 byte = 512 bit(远超 HS256 256 bit 要求)。 */
    public static final int NEW_SECRET_BYTES = 64;

    private final Map<String, KeyRingEntry> entries = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeKid = new AtomicReference<>();
    private volatile long lastRotationAt = 0;
    private final SecureRandom random = new SecureRandom();
    private final org.springframework.core.env.Environment env;

    public KeyRingService(
            @Value("${app.jwt.secret}") String initialSecret,
            @Value("${app.jwt.previous-secret:}") String previousSecret,
            org.springframework.core.env.Environment env
    ) {
        this.env = env;
        if (initialSecret == null || initialSecret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret 必须配置");
        }
        if (initialSecret.getBytes().length < MIN_SECRET_BYTES) {
            log.warn("[keyring] app.jwt.secret 强度不足(< {} bytes),生产环境必须 ≥ 32 字节",
                    MIN_SECRET_BYTES);
        }
        // Stage 1 安全收口:dev 占位 secret 必须硬阻断生产启动。
        // 仅 dev profile 允许;生产环境检测到即抛异常退出(禁 warn-and-continue)。
        boolean isDevProfile = java.util.Arrays.asList(env.getActiveProfiles()).contains("dev");
        if (initialSecret.contains("dev_jwt_secret") && !isDevProfile) {
            throw new IllegalStateException(
                    "[keyring] ⛔  检测到 dev 占位 secret(app.jwt.secret) — "
                            + "生产环境禁止使用占位符,必须由 KMS 注入真实密钥");
        }
        if (initialSecret.contains("dev_jwt_secret")) {
            log.warn("[keyring] 开发环境使用 dev 占位 secret — 生产部署前必须替换为 KMS 注入值");
        }

        registerInitial(initialSecret, "k-0", KeyRingEntry.Status.ACTIVE);

        if (previousSecret != null && !previousSecret.isBlank()) {
            registerInitial(previousSecret, "k-prev", KeyRingEntry.Status.RETIRED);
        }

        log.info("[keyring] 启动: active={}, retired={}",
                activeKid.get(),
                entries.keySet().stream().filter(k -> !k.equals(activeKid.get())).toList());
    }

    private void registerInitial(String secret, String kid, KeyRingEntry.Status status) {
        KeyRingEntry e = new KeyRingEntry(kid, secret, status,
                Instant.now(), status == KeyRingEntry.Status.RETIRED ? Instant.now() : null, null);
        entries.put(kid, e);
        if (status == KeyRingEntry.Status.ACTIVE) {
            activeKid.set(kid);
        }
    }

    /**
     * 轮换:生成新密钥,旧 ACTIVE → RETIRED.
     *
     * @return 新 active entry 的 kid
     * @throws IllegalStateException 速率超限 / 已存在同名 kid
     */
    public synchronized String rotate() {
        long now = System.currentTimeMillis();
        if (now - lastRotationAt < ROTATION_MIN_INTERVAL_MS) {
            throw new IllegalStateException(
                    "轮换速率超限:距上次轮换 " + (now - lastRotationAt) + " ms,需 ≥ "
                            + ROTATION_MIN_INTERVAL_MS + " ms");
        }

        String newSecret = randomHexSecret(NEW_SECRET_BYTES);
        String newKid = "k-" + (entries.size());
        if (entries.containsKey(newKid)) {
            throw new IllegalStateException("kid 冲突: " + newKid);
        }

        // 旧 ACTIVE → RETIRED
        String oldKid = activeKid.get();
        if (oldKid != null) {
            KeyRingEntry old = entries.get(oldKid);
            entries.put(oldKid, new KeyRingEntry(
                    old.kid(), old.secret(), KeyRingEntry.Status.RETIRED,
                    old.createdAt(), Instant.now(), old.revokedAt()));
        }

        // 新 ACTIVE
        entries.put(newKid, KeyRingEntry.active(newKid, newSecret));
        activeKid.set(newKid);
        lastRotationAt = now;
        log.info("[keyring] 轮换完成: active={} (前 {} 已 RETIRED)", newKid, oldKid);
        return newKid;
    }

    /**
     * 显式撤销某个 kid(签名/验证都拒).主要用于紧急轮换后旧 key 提前下线.
     */
    public synchronized void revoke(String kid) {
        KeyRingEntry e = entries.get(kid);
        if (e == null) throw new IllegalArgumentException("未知 kid: " + kid);
        if (kid.equals(activeKid.get())) {
            throw new IllegalStateException("不能撤销当前 active key — 先 rotate");
        }
        entries.put(kid, new KeyRingEntry(
                e.kid(), e.secret(), KeyRingEntry.Status.REVOKED,
                e.createdAt(), e.retiredAt(), Instant.now()));
        log.warn("[keyring] revoked: kid={}", kid);
    }

    /** 通过 kid 查 entry — 用于 JWT header. kid 携带的精确查找。 */
    public Optional<KeyRingEntry> findByKid(String kid) {
        if (kid == null) return Optional.empty();
        return Optional.ofNullable(entries.get(kid));
    }

    /** 取当前 active entry — 用于签发新 token. */
    public Optional<KeyRingEntry> currentActive() {
        String kid = activeKid.get();
        return kid == null ? Optional.empty() : findByKid(kid);
    }

    /** 当 token 没带 kid 时的回退:验证用 active key(G1 兼容 — 老 token 无 kid header)。 */
    public Optional<KeyRingEntry> fallbackActive() {
        return currentActive();
    }

    /** 列出所有 entry(调试/管理端点用)。secret 字段被脱敏。 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (KeyRingEntry e : sortedEntries()) {
            result.add(Map.of(
                    "kid", e.kid(),
                    "status", e.status().name(),
                    "createdAt", e.createdAt().toString(),
                    "retiredAt", e.retiredAt() == null ? "" : e.retiredAt().toString(),
                    "revokedAt", e.revokedAt() == null ? "" : e.revokedAt().toString(),
                    "secretPreview", e.secret().substring(0, 6) + "..."
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private List<KeyRingEntry> sortedEntries() {
        return entries.values().stream()
                .sorted((a, b) -> a.kid().compareTo(b.kid()))
                .toList();
    }

    /** 测试 / 监控: 当前 entry 数量。 */
    public int size() {
        return entries.size();
    }

    static String randomHexSecret(int byteLen) {
        byte[] buf = new byte[byteLen];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
