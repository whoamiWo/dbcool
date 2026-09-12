package com.nocobase.acl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

/** ROW-level ACL 管理接口(Week 14.5). */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Roles & ACL", description = "角色 + ACL 策略")
@RequestMapping("/api/admin/row-acl")
public class RowAclController {

    private final AclRowPolicyRepository repository;
    private final ObjectMapper objectMapper;

    public RowAclController(AclRowPolicyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = repository.findAll().stream()
                .filter(p -> p.getTenantId().equals(user.tenantId()))
                .map(this::toDto).toList();
        return Map.of("code", 0, "data", data);
    }

    @GetMapping("/by-collection/{collection}")
    public Map<String, Object> byCollection(@PathVariable String collection,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = repository
                .findByTenantIdAndCollectionOrderByPriorityDescCreatedAtAsc(user.tenantId(), collection)
                .stream().map(this::toDto).toList();
        return Map.of("code", 0, "data", data);
    }

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
            return Map.of("code", 0, "data", toDto(p));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "创建策略失败: " + e.getMessage());
        }
    }

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
            p.setCollection(req.collection());
            p.setPrincipalType(req.principalType());
            p.setPrincipalId(req.principalId());
            p.setAction(req.action());
            p.setExpression(objectMapper.writeValueAsString(req.expression()));
            if (req.priority() != null) p.setPriority(req.priority());
            if (req.enabled() != null) p.setEnabled(req.enabled());
            p.setDescription(req.description());
            repository.save(p);
            return Map.of("code", 0, "data", toDto(p));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "更新失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        AclRowPolicyEntity p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        repository.delete(p);
        return Map.of("code", 0, "data", Map.of("id", id));
    }

    private Map<String, Object> toDto(AclRowPolicyEntity p) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
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
