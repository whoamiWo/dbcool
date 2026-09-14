package com.nocobase.tenant;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理 API(Week 41 D6 Step G1).
 *
 * <p>端点:
 * <ul>
 *   <li>GET    /api/admin/tenants — 列表</li>
 *   <li>GET    /api/admin/tenants/{id} — 详情</li>
 *   <li>POST   /api/admin/tenants — 新建</li>
 *   <li>DELETE /api/admin/tenants/{id} — 禁用(软删除)</li>
 * </ul>
 *
 * <p>权限:管理员 only(PreAuthorize + ROLE_ADMIN)。
 */
@RestController
@Tag(name = "Tenants", description = "多租户管理")
@RequestMapping("/api/admin/tenants")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> list() {
        List<TenantEntity> all = service.listAll();
        return Map.of("code", 0, "message", "success", "data", all);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> get(@PathVariable String id) {
        TenantEntity t = service.get(id);
        return Map.of("code", 0, "message", "success", "data", t);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateTenantRequest req) {
        TenantEntity t = service.create(req.id(), req.name(), req.slug());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", t));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> disable(@PathVariable String id) {
        TenantEntity t = service.disable(id);
        return Map.of("code", 0, "message", "disabled", "data", t);
    }

    public record CreateTenantRequest(String id, String name, String slug) {}
}
