package com.nocobase.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户-租户关联 API(US-504 应用切换).
 *
 * <p>端点:
 * <ul>
 *   <li>GET    /api/admin/users/{userId}/tenants — 当前用户可切换的租户列表</li>
 *   <li>POST   /api/admin/users/{userId}/tenants — 关联用户到租户</li>
 *   <li>DELETE /api/admin/users/{userId}/tenants/{tenantId} — 解除关联</li>
 * </ul>
 */
@RestController
@Tag(name = "User Tenants", description = "用户-租户关联(应用切换)")
@RequestMapping("/api/admin")
public class UserTenantController {

    private final UserTenantRepository userTenantRepo;
    private final TenantRepository tenantRepo;

    public UserTenantController(UserTenantRepository userTenantRepo, TenantRepository tenantRepo) {
        this.userTenantRepo = userTenantRepo;
        this.tenantRepo = tenantRepo;
    }

    @GetMapping("/users/{userId}/tenants")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listByUser(@PathVariable String userId) {
        List<UserTenantEntity> list = userTenantRepo.findByUserId(userId);
        // 关联实体信息,便于前端展示
        List<Map<String, Object>> data = list.stream().map(ute -> {
            var t = tenantRepo.findById(ute.getTenantId());
            return Map.<String, Object>of(
                    "user_id", ute.getUserId(),
                    "tenant_id", ute.getTenantId(),
                    "tenant_name", t.map(TenantEntity::getName).orElse(ute.getTenantId()),
                    "created_at", ute.getCreatedAt().toString()
            );
        }).toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PostMapping("/users/{userId}/tenants")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> link(@PathVariable String userId,
                                                    @RequestBody LinkRequest req) {
        if (userTenantRepo.existsByUserIdAndTenantId(userId, req.tenantId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", 1, "message", "用户已关联该租户"));
        }
        var entity = new UserTenantEntity(UUID.randomUUID().toString(), userId, req.tenantId());
        userTenantRepo.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", Map.of(
                        "user_id", userId, "tenant_id", req.tenantId())));
    }

    @DeleteMapping("/users/{userId}/tenants/{tenantId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> unlink(@PathVariable String userId, @PathVariable String tenantId) {
        userTenantRepo.deleteByUserIdAndTenantId(userId, tenantId);
        return Map.of("code", 0, "message", "deleted");
    }

    public record LinkRequest(String tenantId) {}
}
