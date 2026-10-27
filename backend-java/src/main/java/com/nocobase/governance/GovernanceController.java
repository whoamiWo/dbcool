package com.nocobase.governance;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据治理 REST API(B7)。
 *
 * <p>端点(全部挂 JWT 认证):
 * <ul>
 *   <li>{@code /api/governance/retention}        保留策略 CRUD</li>
 *   <li>{@code /api/governance/quality}          质量规则 CRUD + 执行校验</li>
 *   <li>{@code /api/governance/lineage/{name}}   血缘查询</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final DataGovernanceService governance;
    private final AuditService auditService;

    public GovernanceController(DataGovernanceService governance, AuditService auditService) {
        this.governance = governance;
        this.auditService = auditService;
    }

    // ---------- 保留策略 ----------

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/retention")
    public Map<String, Object> createPolicy(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.requireTenantId();
        RetentionPolicyEntity p = governance.createPolicy(
                str(body.get("collectionName")),
                str(body.get("action")),
                body.get("retentionDays") == null ? null : Integer.valueOf(String.valueOf(body.get("retentionDays"))),
                body.get("enabled") == null ? true : Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        auditService.log(tenantId, null, null, "CREATE_RETENTION_POLICY", "governance/retention", String.valueOf(p.getId()), body);
        return Map.of("code", 0, "message", "created",
                "data", Map.of("id", String.valueOf(p.getId())));
    }

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/retention")
    public Map<String, Object> listPolicies() {
        String tenantId = TenantContext.requireTenantId();
        List<RetentionPolicyEntity> list = governance.listPolicies();
        return Map.of("code", 0, "message", "success",
                "data", Map.of("policies", list));
    }

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/retention/{id}/execute")
    public Map<String, Object> executePolicy(@PathVariable UUID id) {
        auditService.log(TenantContext.requireTenantId(), null, null, "EXECUTE_RETENTION_POLICY", "governance/retention", String.valueOf(id), null);
        Map<String, Object> r = governance.executePolicy(id);
        return Map.of("code", 0, "message", "executed", "data", r);
    }

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @DeleteMapping("/retention/{id}")
    public Map<String, Object> deletePolicy(@PathVariable UUID id) {
        auditService.log(TenantContext.requireTenantId(), null, null, "DELETE_RETENTION_POLICY", "governance/retention", String.valueOf(id), null);
        governance.deletePolicy(id);
        return Map.of("code", 0, "message", "deleted");
    }

    // ---------- 质量规则 ----------

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/quality")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.requireTenantId();
        DataQualityRuleEntity r = governance.createRule(
                str(body.get("collectionName")),
                str(body.get("fieldName")),
                str(body.get("ruleType")),
                body.get("ruleConfig") instanceof Map<?, ?> m ? castMap(m) : null,
                body.get("severity") == null ? "WARN" : String.valueOf(body.get("severity")),
                body.get("enabled") == null ? true : Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        auditService.log(tenantId, null, null, "CREATE_QUALITY_RULE", "governance/quality", String.valueOf(r.getId()), body);
        return Map.of("code", 0, "message", "created",
                "data", Map.of("id", String.valueOf(r.getId())));
    }

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/quality")
    public Map<String, Object> listRules() {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("rules", governance.listRules()));
    }

    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @DeleteMapping("/quality/{id}")
    public Map<String, Object> deleteRule(@PathVariable UUID id) {
        auditService.log(TenantContext.requireTenantId(), null, null, "DELETE_QUALITY_RULE", "governance/quality", String.valueOf(id), null);
        governance.deleteRule(id);
        return Map.of("code", 0, "message", "deleted");
    }

    // ---------- 血缘 ----------

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/lineage/{collectionName}")
    public ResponseEntity<Map<String, Object>> lineage(@PathVariable String collectionName) {
        Map<String, Object> lineage = governance.lineage(collectionName);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", lineage));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
