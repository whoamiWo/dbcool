package com.nocobase.apikey;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * API Key 管理端点(Week 42 D5.2).
 *
 * <p>管理端点需 admin 角色(目前允许任何已认证用户 — Week 42 D7 契约补全时
 * 改为 hasRole("ADMIN"))。
 *
 * <p>注意:{@code POST} 创建时返回 raw key,这是用户能看到明文的唯一时机。
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "API Keys", description = "API Key 管理")
@RequestMapping("/api/admin/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> list() {
        List<Map<String, Object>> data = service.listActive(TenantContext.currentTenantId())
                .stream()
                .map(this::toDto)
                .toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreateKeyRequest req,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 必填");
        }
        Instant expiresAt = null;
        if (req.expiresInDays() != null && req.expiresInDays() > 0) {
            expiresAt = Instant.now().plusSeconds(req.expiresInDays() * 86400L);
        }
        ApiKeyService.CreatedKey created = service.create(
                req.name(), req.scopes(), expiresAt,
                TenantContext.currentTenantId(), user.userId());

        Map<String, Object> body = Map.of(
                "code", 0, "message", "success",
                "data", Map.of(
                        "rawKey", created.rawKey(),
                        "keyPrefix", created.entity().getKeyPrefix(),
                        "name", created.entity().getName(),
                        "scopes", created.entity().getScopes() == null ? "" : created.entity().getScopes(),
                        "expiresAt", created.entity().getExpiresAt() == null ? "" : created.entity().getExpiresAt().toString(),
                        "id", created.entity().getId().toString(),
                        "_warning", "请立即保存 rawKey,这是唯一可见时机"
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revoke(@PathVariable UUID id) {
        boolean ok = service.revoke(id, TenantContext.currentTenantId());
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key 不存在或不属于当前租户");
        }
        return Map.of("code", 0, "message", "revoked", "data", Map.of("id", id.toString()));
    }

    private Map<String, Object> toDto(ApiKeyEntity e) {
        return Map.of(
                "id", e.getId().toString(),
                "name", e.getName(),
                "keyPrefix", e.getKeyPrefix(),
                "scopes", e.getScopes() == null ? "" : e.getScopes(),
                "createdAt", e.getCreatedAt().toString(),
                "lastUsedAt", e.getLastUsedAt() == null ? "" : e.getLastUsedAt().toString(),
                "expiresAt", e.getExpiresAt() == null ? "" : e.getExpiresAt().toString()
        );
    }

    public record CreateKeyRequest(
            String name,
            String scopes,
            Integer expiresInDays
    ) {}
}
