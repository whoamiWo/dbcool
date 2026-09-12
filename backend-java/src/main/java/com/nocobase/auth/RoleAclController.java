package com.nocobase.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 角色 + ACL 策略 API(US-302 ~ US-307 + US-308 角色继承).
 */
@RestController
@Tag(name = "Roles & ACL", description = "角色 + ACL 策略 + 角色继承")
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
        r.setParentRoleId(req.parentRoleId());  // US-308
        r.setTenantId("tenant_default");
        r.setCreatedAt(Instant.now());
        if (req.parentRoleId() != null) {
            assertNoCycle(r.getId(), req.parentRoleId());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", roleDto(roleRepository.save(r))));
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> updateRole(@PathVariable UUID id, @RequestBody UpdateRoleRequest req) {
        RoleEntity r = roleRepository.findByIdAndTenantId(id, "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role 不存在"));
        if (req.name() != null) r.setName(req.name());
        if (req.description() != null) r.setDescription(req.description());
        if (req.parentRoleId() != null) {
            assertNoCycle(r.getId(), req.parentRoleId());
            r.setParentRoleId(req.parentRoleId());
        }
        return Map.of("code", 0, "message", "success", "data", roleDto(roleRepository.save(r)));
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable UUID id) {
        // 清空子角色引用此 role 的 parent_role_id(避免 FK 失败)
        for (RoleEntity child : roleRepository.findByParentRoleId(id)) {
            child.setParentRoleId(null);
            roleRepository.save(child);
        }
        aclRepository.findByRoleIdAndTenantId(id, "tenant_default")
                .forEach(p -> aclRepository.deleteById(p.getId()));
        roleRepository.deleteById(id);
        return Map.of("code", 0, "message", "deleted");
    }

    /**
     * US-308: 返回所有 role 的树形结构(按 parent_role_id 嵌套).
     */
    @GetMapping("/roles/tree")
    public Map<String, Object> roleTree() {
        List<RoleEntity> all = roleRepository.findByTenantIdOrderByCreatedAt("tenant_default");
        Map<UUID, List<RoleEntity>> byParent = all.stream()
                .filter(r -> r.getParentRoleId() != null)
                .collect(Collectors.groupingBy(RoleEntity::getParentRoleId));
        List<Map<String, Object>> roots = all.stream()
                .filter(r -> r.getParentRoleId() == null)
                .map(r -> toTreeNode(r, byParent))
                .toList();
        return Map.of("code", 0, "message", "success", "data", roots);
    }

    /**
     * US-308: 查询一个角色的完整继承链(self + ancestors).
     */
    @GetMapping("/roles/{id}/inheritance")
    public Map<String, Object> inheritanceChain(@PathVariable UUID id) {
        RoleEntity r = roleRepository.findByIdAndTenantId(id, "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role 不存在"));
        List<UUID> chain = roleRepository.findSelfAndAncestors(id, "tenant_default");
        List<Map<String, Object>> nodes = chain.stream()
                .map(rid -> roleRepository.findById(rid).orElse(null))
                .filter(Objects::nonNull)
                .map(this::roleDto)
                .toList();
        return Map.of("code", 0, "message", "success",
                "data", Map.of(
                        "role", roleDto(r),
                        "chain", nodes,
                        "depth", nodes.size() - 1));
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
    //  helpers
    // ============================================================

    private Map<String, Object> roleDto(RoleEntity r) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", r.getId().toString());
        dto.put("name", r.getName());
        dto.put("description", r.getDescription() != null ? r.getDescription() : "");
        dto.put("tenant_id", r.getTenantId());
        dto.put("parent_role_id", r.getParentRoleId() != null ? r.getParentRoleId().toString() : null);
        dto.put("created_at", r.getCreatedAt().toString());
        return dto;
    }

    private Map<String, Object> toTreeNode(RoleEntity r, Map<UUID, List<RoleEntity>> byParent) {
        Map<String, Object> node = new LinkedHashMap<>(roleDto(r));
        List<RoleEntity> children = byParent.getOrDefault(r.getId(), List.of());
        if (!children.isEmpty()) {
            node.put("children", children.stream()
                    .map(c -> toTreeNode(c, byParent))
                    .toList());
        }
        return node;
    }

    private Map<String, Object> aclDto(AclPolicyEntity p) {
        Map<String, Object> dto = new LinkedHashMap<>();
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

    /**
     * 检查「把 role 设为 candidate 的 parent」是否形成循环。
     * 即:role 不能是自己的祖先。
     */
    private void assertNoCycle(UUID roleId, UUID candidateParentId) {
        if (roleId.equals(candidateParentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不能将自己设为 parent(自引用)");
        }
        // candidateParent 的祖先中不能包含 roleId
        List<UUID> ancestors = roleRepository.findSelfAndAncestors(candidateParentId, "tenant_default");
        if (ancestors.contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "parent_role_id 形成循环:目标 parent 已是当前 role 的后代");
        }
    }

    public record CreateRoleRequest(
            @NotBlank String name,
            String description,
            @JsonProperty("parent_role_id") UUID parentRoleId
    ) {}

    public record UpdateRoleRequest(
            String name,
            String description,
            @JsonProperty("parent_role_id") UUID parentRoleId
    ) {}

    public record CreateAclRequest(
            UUID roleId,
            @NotBlank String type,
            @NotBlank String subject,
            String action,
            String config
    ) {}

    public record UpdateAclRequest(String action, String config) {}
}
