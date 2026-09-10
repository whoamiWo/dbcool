package com.nocobase.auth;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理 API(US-301, 平台 Admin 用).
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminService userService;
    private final UserRoleRepository userRoleRepository;

    public UserAdminController(UserAdminService userService, UserRoleRepository userRoleRepository) {
        this.userService = userService;
        this.userRoleRepository = userRoleRepository;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("code", 0, "message", "success",
                "data", userService.listAll().stream().map(this::toDto).toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Map<String, Object> dto = toDto(userService.get(id));
        dto.put("roles", userService.getUserRoles(id).stream().map(this::toRoleDto).toList());
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody @Valid CreateUserRequest req) {
        UserEntity u = userService.create(req.username(), req.password(), req.displayName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", toDto(u)));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateUserRequest req) {
        UserEntity u = userService.update(id, req.displayName(), req.enabled());
        return Map.of("code", 0, "message", "success", "data", toDto(u));
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("password"));
        return Map.of("code", 0, "message", "password reset");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        userRoleRepository.deleteByIdUserId(id);
        userService.delete(id);
        return Map.of("code", 0, "message", "deleted");
    }

    @PostMapping("/{id}/roles/{roleId}")
    public Map<String, Object> assignRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        userService.assignRole(id, roleId);
        return Map.of("code", 0, "message", "assigned");
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    public Map<String, Object> removeRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        userService.removeRole(id, roleId);
        return Map.of("code", 0, "message", "removed");
    }

    @GetMapping("/{id}/effective-permissions")
    public Map<String, Object> effectivePermissions(@PathVariable UUID id) {
        return Map.of("code", 0, "message", "success",
                "data", userService.getEffectivePermissions(id));
    }

    private Map<String, Object> toDto(UserEntity u) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", u.getId().toString());
        dto.put("username", u.getUsername());
        dto.put("display_name", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        dto.put("tenant_id", u.getTenantId());
        dto.put("enabled", u.isEnabled());
        dto.put("created_at", u.getCreatedAt().toString());
        return dto;
    }

    private Map<String, Object> toRoleDto(RoleEntity r) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", r.getId().toString());
        dto.put("name", r.getName());
        dto.put("description", r.getDescription() != null ? r.getDescription() : "");
        return dto;
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank String password,
            String displayName
    ) {}

    public record UpdateUserRequest(
            String displayName,
            Boolean enabled
    ) {}
}
