package com.nocobase.audit;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志查询端点 — admin 角色可看全量(Week 44 D1 修复:原无鉴权,任何已认证用户可读)。 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Audit", description = "审计日志")
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> list(
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<AuditLogEntity> rows = service.find(user.tenantId(), resource, action, userId, limit);
        long total = service.count(user.tenantId());
        List<Map<String, Object>> data = rows.stream().map(this::toDto).toList();
        return Map.of("code", 0, "message", "success",
                "data", Map.of("total", total, "logs", data));
    }

    private Map<String, Object> toDto(AuditLogEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId().toString());
        m.put("user_id", e.getUserId());
        m.put("username", e.getUsername());
        m.put("action", e.getAction());
        m.put("resource", e.getResource());
        m.put("resource_id", e.getResourceId());
        m.put("payload_json", e.getPayloadJson());
        m.put("ip", e.getIp());
        m.put("user_agent", e.getUserAgent());
        m.put("created_at", e.getCreatedAt().toString());
        return m;
    }
}
