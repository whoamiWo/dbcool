package com.nocobase.auth;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.keystore.KeyRingService;
import com.nocobase.tenant.TenantContext;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JWT 密钥轮换管理端点(Week 42 R10).
 *
 * <p>当前允许任何已认证用户访问 — Week 43+ D7 契约补全时改 hasRole("ADMIN").
 *
 * <p><strong>轮换操作不可逆</strong>:已签发的 token 若 kid 是被 RETIRED 的 key,
 * 仍可验证通过直到 access TTL (默认 15 min) 到期。要立即废止,先 revoke 再让活跃用户重登。
 *
 * <p>rotation 后<strong>必须</strong>同步更新 yml 的 {@code app.jwt.previous-secret}
 * 为 RETIRED key 的 secret,否则进程重启后该 RETIRED key 会丢失,所有老 token 失效。
 * MVP 阶段 rotation 只在进程内存生效(重启丢失);真实 KMS 集成在 Week 43+。
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "JWT Keys", description = "签名密钥轮换管理")
@RequestMapping("/api/admin/jwt-keys")
public class JwtKeyRotationController {

    private final KeyRingService keyRing;
    private final AuditService auditService;

    public JwtKeyRotationController(KeyRingService keyRing, AuditService auditService) {
        this.keyRing = keyRing;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        return Map.of(
                "code", 0, "message", "success",
                "data", Map.of(
                        "size", keyRing.size(),
                        "keys", keyRing.snapshot()
                )
        );
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotate() {
        try {
            String newKid = keyRing.rotate();
            auditService.log(TenantContext.currentTenantId(), "system", "system",
                    "jwt_key.rotate", "jwt_key", newKid,
                    Map.of("newKid", newKid));
            return Map.of(
                    "code", 0, "message", "rotated",
                    "data", Map.of(
                            "newActiveKid", newKid,
                            "_warning", "rotation 仅在进程内生效 — 重启后从 yml 重读。"
                                    + "请同步更新 app.jwt.previous-secret 配置以保留旧 key 验证能力。"
                    )
            );
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
    }
}