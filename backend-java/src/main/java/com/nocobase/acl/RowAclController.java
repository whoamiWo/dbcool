package com.nocobase.acl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Roles & ACL", description = "角色 + ACL 策略")
@RequestMapping("/api/admin/row-acl")
public class RowAclController {

    private final AclRowPolicyRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public RowAclController(AclRowPolicyRepository repository, ObjectMapper objectMapper, AuditService auditService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = repository.findAll().stream()
                .filter(p -> p.getTenantId().equals(user.tenantId()))
                .map(this::toDto).toList();
        return Map.of("code", 0, "data", data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/by-collection/{collection}")
    public Map<String, Object> byCollection(@PathVariable String collection,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = repository
                .findByTenantIdAndCollectionOrderByPriorityDescCreatedAtAsc(user.tenantId(), collection)
                .stream().map(this::toDto).toList();
        return Map.of("code", 0, "data", data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Map<String, Object> create(@RequestBody PolicyRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            AclRowPolicyEntity p = new AclRowPolicyEntity();
            p.setId(UUID.randomUUID());
            p.setTenantId(user.tenantId());
            p.setCollection(req.collection());
            p.setPrincipalType(req.principalType());
            p.setPrincipalId(req.principalId());
            p.setAction(req.action());
            p.setExpression(objectMapper.writeValueAsString(req.expression()));
            p.setPriority(req.priority() == null ? 0 : req.priority());
            p.setEnabled(req.enabled() == null || req.enabled());
            p.setDescription(req.description());
            repository.save(p);
            auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                    "row_acl.policy.create", "row_acl", p.getId().toString(),
                    auditPayload("collection", req.collection(), "principal_type", req.principalType(),
                            "principal_id", req.principalId(), "action", req.action(),
                            "priority", req.priority(), "enabled", req.enabled()));
            return Map.of("code", 0, "data", toDto(p));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "创建策略失败: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id,
                                      @RequestBody PolicyRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        AclRowPolicyEntity p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            AclRowPolicyEntity before = clone(p);
            p.setCollection(req.collection());
            p.setPrincipalType(req.principalType());
            p.setPrincipalId(req.principalId());
            p.setAction(req.action());
            p.setExpression(objectMapper.writeValueAsString(req.expression()));
            if (req.priority() != null) p.setPriority(req.priority());
            if (req.enabled() != null) p.setEnabled(req.enabled());
            p.setDescription(req.description());
            repository.save(p);
            auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                    "row_acl.policy.update", "row_acl", p.getId().toString(),
                    auditPayload("before", toDto(before), "after", toDto(p)));
            return Map.of("code", 0, "data", toDto(p));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "更新失败: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        AclRowPolicyEntity p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Map<String, Object> dto = toDto(p);
        repository.delete(p);
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "row_acl.policy.delete", "row_acl", id.toString(),
                auditPayload("collection", p.getCollection(), "principal_id", p.getPrincipalId()));
        return Map.of("code", 0, "data", Map.of("id", id));
    }

    private Map<String, Object> auditPayload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private AclRowPolicyEntity clone(AclRowPolicyEntity src) {
        AclRowPolicyEntity copy = new AclRowPolicyEntity();
        copy.setId(src.getId());
        copy.setTenantId(src.getTenantId());
        copy.setCollection(src.getCollection());
        copy.setPrincipalType(src.getPrincipalType());
        copy.setPrincipalId(src.getPrincipalId());
        copy.setAction(src.getAction());
        copy.setExpression(src.getExpression());
        copy.setPriority(src.getPriority());
        copy.setEnabled(src.isEnabled());
        copy.setDescription(src.getDescription());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    private Map<String, Object> toDto(AclRowPolicyEntity p) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId().toString());
        m.put("collection", p.getCollection());
        m.put("principal_type", p.getPrincipalType());
        m.put("principal_id", p.getPrincipalId());
        m.put("action", p.getAction());
        try {
            m.put("expression", objectMapper.readValue(
                    p.getExpression(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            m.put("expression", Map.of("_error", e.getMessage()));
        }
        m.put("priority", p.getPriority());
        m.put("enabled", p.isEnabled());
        m.put("description", p.getDescription() == null ? "" : p.getDescription());
        m.put("created_at", p.getCreatedAt().toString());
        m.put("updated_at", p.getUpdatedAt().toString());
        return m;
    }

    public record PolicyRequest(
            String collection,
            @com.fasterxml.jackson.annotation.JsonProperty("principal_type") String principalType,
            @com.fasterxml.jackson.annotation.JsonProperty("principal_id") String principalId,
            String action,
            Map<String, Object> expression,
            Integer priority,
            Boolean enabled,
            String description
    ) {}
}