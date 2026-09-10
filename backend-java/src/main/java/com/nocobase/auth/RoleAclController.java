package com.nocobase.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 角色 + ACL 策略 API(US-302 ~ US-307).
 */
@RestController
@RequestMapping("/api/admin")
public class RoleAclController {

    private final RoleRepository roleRepository;
    private final AclPolicyRepository aclRepository;

    public RoleAclController(RoleRepository roleRepository, AclPolicyRepository aclRepository) {
        this.roleRepository = roleRepository;
        this.aclRepository = aclRepository;
    }

    // ============================================================
    //  Roles
    // ============================================================

    @GetMapping("/roles")
    public Map<String, Object> listRoles() {
        return Map.of("code", 0, "message", "success",
                "data", roleRepository.findByTenantIdOrderByCreatedAt("tenant_default")
                        .stream().map(this::roleDto).toList());
    }

    @PostMapping("/roles")
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody @Valid CreateRoleRequest req) {
        if (roleRepository.existsByNameAndTenantId(req.name(), "tenant_default")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色名已存在");
        }
        RoleEntity r = new RoleEntity();
        r.setId(UUID.randomUUID());
        r.setName(req.name());
        r.setDescription(req.description());
        r.setTenantId("tenant_default");
        r.setCreatedAt(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", roleDto(roleRepository.save(r))));
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> updateRole(@PathVariable UUID id, @RequestBody UpdateRoleRequest req) {
        RoleEntity r = roleRepository.findByIdAndTenantId(id, "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role 不存在"));
        if (req.name() != null) r.setName(req.name());
        if (req.description() != null) r.setDescription(req.description());
        return Map.of("code", 0, "message", "success", "data", roleDto(roleRepository.save(r)));
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable UUID id) {
        aclRepository.findByRoleIdAndTenantId(id, "tenant_default")
                .forEach(p -> aclRepository.deleteById(p.getId()));
        roleRepository.deleteById(id);
        return Map.of("code", 0, "message", "deleted");
    }

    // ============================================================
    //  ACL Policies
    // ============================================================

    @GetMapping("/acl")
    public Map<String, Object> listAcl(@RequestParam UUID roleId) {
        return Map.of("code", 0, "message", "success",
                "data", aclRepository.findByRoleIdAndTenantId(roleId, "tenant_default")
                        .stream().map(this::aclDto).toList());
    }

    @PostMapping("/acl")
    public ResponseEntity<Map<String, Object>> createAcl(@RequestBody @Valid CreateAclRequest req) {
        roleRepository.findByIdAndTenantId(req.roleId(), "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role 不存在"));
        AclPolicyEntity p = new AclPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setRoleId(req.roleId());
        p.setType(AclPolicyEntity.Type.valueOf(req.type().toUpperCase()));
        p.setSubject(req.subject());
        if (req.action() != null) p.setAction(AclPolicyEntity.Action.valueOf(req.action().toUpperCase()));
        p.setConfigJson(req.config() != null ? req.config() : "{}");
        p.setTenantId("tenant_default");
        p.setCreatedAt(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", aclDto(aclRepository.save(p))));
    }

    @PutMapping("/acl/{id}")
    public Map<String, Object> updateAcl(@PathVariable UUID id, @RequestBody UpdateAclRequest req) {
        AclPolicyEntity p = aclRepository.findByIdAndTenantId(id, "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ACL 不存在"));
        if (req.config() != null) p.setConfigJson(req.config());
        if (req.action() != null) p.setAction(AclPolicyEntity.Action.valueOf(req.action().toUpperCase()));
        return Map.of("code", 0, "message", "success", "data", aclDto(aclRepository.save(p)));
    }

    @DeleteMapping("/acl/{id}")
    public Map<String, Object> deleteAcl(@PathVariable UUID id) {
        aclRepository.deleteById(id);
        return Map.of("code", 0, "message", "deleted");
    }

    // ============================================================
    //  DTOs
    // ============================================================

    private Map<String, Object> roleDto(RoleEntity r) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", r.getId().toString());
        dto.put("name", r.getName());
        dto.put("description", r.getDescription() != null ? r.getDescription() : "");
        dto.put("tenant_id", r.getTenantId());
        dto.put("created_at", r.getCreatedAt().toString());
        return dto;
    }

    private Map<String, Object> aclDto(AclPolicyEntity p) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", p.getId().toString());
        dto.put("role_id", p.getRoleId().toString());
        dto.put("type", p.getType().name());
        dto.put("subject", p.getSubject());
        dto.put("action", p.getAction() != null ? p.getAction().name() : null);
        dto.put("config_json", p.getConfigJson());
        dto.put("tenant_id", p.getTenantId());
        dto.put("created_at", p.getCreatedAt().toString());
        return dto;
    }

    public record CreateRoleRequest(@NotBlank String name, String description) {}
    public record UpdateRoleRequest(String name, String description) {}

    public record CreateAclRequest(
            java.util.UUID roleId,
            @NotBlank String type,
            @NotBlank String subject,
            String action,
            String config
    ) {}

    public record UpdateAclRequest(String action, String config) {}
}
