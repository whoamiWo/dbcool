package com.nocobase.ldap;

import com.nocobase.ldap.entity.LdapConfigEntity;
import com.nocobase.ldap.entity.LdapUserMappingEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LDAP/AD 同步 REST API。
 */
@RestController
@Tag(name = "LDAP Sync", description = "LDAP/AD 用户同步")
@RequestMapping("/api/ldap")
public class LdapSyncController {

    private final LdapSyncService ldapSyncService;

    public LdapSyncController(LdapSyncService ldapSyncService) {
        this.ldapSyncService = ldapSyncService;
    }

    /** 创建 LDAP 配置 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createConfig(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        LdapConfigEntity config = ldapSyncService.createConfig(
                (String) body.get("name"),
                (String) body.get("serverUrl"),
                (String) body.get("baseDn"),
                (String) body.get("bindDn"),
                (String) body.get("bindPassword"),
                (String) body.get("userSearchFilter"),
                (String) body.get("groupSearchFilter"),
                (Map<String, String>) body.get("attributeMapping"),
                (Integer) body.get("syncInterval"),
                user.tenantId(),
                user.userId()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0,
                "message", "success",
                "data", config
        ));
    }

    /** 启用/禁用同步 */
    @PostMapping("/{configId}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable UUID configId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        boolean enabled = body.getOrDefault("enabled", true) instanceof Boolean b && b;
        LdapConfigEntity config = ldapSyncService.toggleConfig(configId, enabled);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("enabled", config.getEnabled())
        ));
    }

    /** 手动触发同步 */
    @PostMapping("/{configId}/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @PathVariable UUID configId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        Map<String, Object> result = ldapSyncService.syncUsers(configId, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", result
        ));
    }

    /** 列出 LDAP 配置 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<LdapConfigEntity> configs = ldapSyncService.listConfigs(user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("configs", configs)
        ));
    }

    /** 获取映射列表 */
    @GetMapping("/{configId}/mappings")
    public ResponseEntity<Map<String, Object>> listMappings(
            @PathVariable UUID configId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<LdapUserMappingEntity> mappings = ldapSyncService.listMappings(user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("mappings", mappings)
        ));
    }
}
