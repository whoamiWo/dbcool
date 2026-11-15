package com.nocobase.auth;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.keystore.KeyRingService;
import com.nocobase.tenant.TenantContext;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p><strong>2.1.5 发布约束</strong>：{@code app.jwt.rotation.enabled} 默认 {@code false}。
 * 原因是进程内随机 key 不持久化、也不与 Python 后端共享；一旦轮换，Python 只认
 * 共享 {@code JWT_SECRET}(kid=k-0)，{@code /api/ai/*} 与 {@code /api/admin/backup}
 * 会全部 401。持久化/共享 keyring 在 2.1.6 提供，在此之前 rotate 明确返回 409，
 * 只读 snapshot 仍然可用。
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "JWT Keys", description = "签名密钥轮换管理")
@RequestMapping("/api/admin/jwt-keys")
public class JwtKeyRotationController {

    private final KeyRingService keyRing;
    private final AuditService auditService;
    private final boolean rotationEnabled;

    public JwtKeyRotationController(
            KeyRingService keyRing,
            AuditService auditService,
            @Value("${app.jwt.rotation.enabled:false}") boolean rotationEnabled) {
        this.keyRing = keyRing;
        this.auditService = auditService;
        this.rotationEnabled = rotationEnabled;
    }

    @GetMapping
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> snapshot() {
        return Map.of(
                "code", 0, "message", "success",
                "data", Map.of(
                        "size", keyRing.size(),
                        "enabled", rotationEnabled,
                        "keys", keyRing.snapshot()
                )
        );
    }

    @PostMapping("/rotate")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> rotate() {
        if (!rotationEnabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "JWT 密钥轮换在 2.1.5 默认禁用（JWT_ROTATION_ENABLED=false）："
                            + "当前实现只在进程内生成随机 key，不持久化且无法与 Python 共享验签。"
                            + "持久化/共享 keyring 计划于 2.1.6 提供；在此之前请继续使用共享 JWT_SECRET。");
        }
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