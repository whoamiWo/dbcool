package com.nocobase.quota;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import com.nocobase.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 租户配额管理 API (PHASE92 TN-2)。
 *
 * <p>端点:
 * <ul>
 *   <li>GET  /api/admin/tenants/{id}/quota — 查询配额 (管理员)</li>
 *   <li>PUT  /api/admin/tenants/{id}/quota — 设置配额 (管理员)</li>
 *   <li>GET  /api/tenant/quota — 当前租户配额 (登录用户, 自助查询)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class TenantQuotaController {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaController.class);

    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public TenantQuotaController(TenantRepository tenantRepository, AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    /**
     * 管理员查询租户配额
     */
    @GetMapping("/admin/tenants/{id}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getQuotaForAdmin(@PathVariable String id,
                                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", tenant));
    }

    /**
     * 管理员设置租户配额
     */
    @PutMapping("/admin/tenants/{id}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> setQuotaForAdmin(
        @PathVariable String id,
        @RequestBody UpdateQuotaRequest req,
        @AuthenticationPrincipal AuthenticatedUser user) {

        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));

        if (req.apiCallLimit() != null) tenant.setApiCallLimit(req.apiCallLimit());
        if (req.storageLimit() != null) tenant.setStorageLimit(req.storageLimit());
        if (req.seatsLimit() != null) tenant.setSeatsLimit(req.seatsLimit());

        TenantEntity saved = tenantRepository.save(tenant);
        // 注意：Map.of() **不接受 null**，而请求体允许只传部分字段（其余为 null）。
        // 原实现因此抛 NPE → 接口 500（PHASE92 端到端验证时实测踩到）。
        Map<String, Object> payload = new java.util.HashMap<>();
        if (req.apiCallLimit() != null) payload.put("apiCallLimit", req.apiCallLimit());
        if (req.storageLimit() != null) payload.put("storageLimit", req.storageLimit());
        if (req.seatsLimit() != null) payload.put("seatsLimit", req.seatsLimit());
        auditService.log(id, user.userId(), user.username(),
            "quota.update", "tenant_quota", id, payload);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", saved));
    }

    /**
     * 登录用户查询自己租户配额
     */
    @GetMapping("/tenant/quota")
    public ResponseEntity<Map<String, Object>> getMyQuota(@AuthenticationPrincipal AuthenticatedUser user) {
        TenantEntity tenant = tenantRepository.findById(user.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + user.tenantId()));
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", tenant));
    }

    public record UpdateQuotaRequest(Long apiCallLimit, Long storageLimit, Long seatsLimit) {}
}