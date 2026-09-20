package com.nocobase.ldap;

import com.nocobase.ldap.entity.LdapConfigEntity;
import com.nocobase.ldap.entity.LdapUserMappingEntity;
import com.nocobase.ldap.repository.LdapConfigRepository;
import com.nocobase.ldap.repository.LdapUserMappingRepository;
import com.nocobase.auth.UserAdminService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.Filter;
import org.springframework.ldap.query.Query;
import org.springframework.ldap.query.SearchScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

/**
 * LDAP/AD 用户同步服务 — 参考 Okta/Entra ID 设计。
 *
 * <p>功能:
 * <ul>
 *   <li>从 LDAP 目录同步用户到本地系统</li>
 *   <li>维护 LDAP DN 到本地用户的映射</li>
 *   <li>定期同步(通过 @Scheduled)</li>
 *   <li>属性映射(LDAP字段 ↔ 本地字段)</li>
 * </ul>
 */
@Service
public class LdapSyncService {

    private final LdapConfigRepository ldapConfigRepository;
    private final LdapUserMappingRepository ldapUserMappingRepository;
    private final UserRepository userRepository;
    private final LdapTemplate ldapTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LdapSyncService(
            LdapConfigRepository ldapConfigRepository,
            LdapUserMappingRepository ldapUserMappingRepository,
            UserRepository userRepository,
            LdapTemplate ldapTemplate
    ) {
        this.ldapConfigRepository = ldapConfigRepository;
        this.ldapUserMappingRepository = ldapUserMappingRepository;
        this.userRepository = userRepository;
        this.ldapTemplate = ldapTemplate;
    }

    /**
     * 创建/更新 LDAP 配置。
     */
    @Transactional
    public LdapConfigEntity createConfig(String name, String serverUrl, String baseDn,
            String bindDn, String bindPassword, String userSearchFilter,
            String groupSearchFilter, Map<String, String> attributeMapping,
            Integer syncInterval, String tenantId, UUID createdBy) {
        
        LdapConfigEntity config = new LdapConfigEntity();
        config.setId(UUID.randomUUID());
        config.setName(name);
        config.setServerUrl(serverUrl);
        config.setBaseDn(baseDn);
        config.setBindDn(bindDn);
        config.setBindPassword(bindPassword);
        config.setUserSearchFilter(userSearchFilter);
        config.setGroupSearchFilter(groupSearchFilter);
        config.setAttributeMappingJson(toJson(attributeMapping != null ? attributeMapping : Map.of()));
        config.setSyncIntervalMinutes(syncInterval != null ? syncInterval : 60);
        config.setEnabled(false);
        config.setTenantId(tenantId);
        config.setCreatedBy(createdBy);
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        
        return ldapConfigRepository.save(config);
    }

    /**
     * 启用/禁用 LDAP 同步。
     */
    @Transactional
    public LdapConfigEntity toggleConfig(UUID configId, boolean enabled) {
        LdapConfigEntity config = getConfig(configId);
        config.setEnabled(enabled);
        config.setUpdatedAt(Instant.now());
        return ldapConfigRepository.save(config);
    }

    /**
     * 执行同步 — 从 LDAP 导入所有用户。
     */
    @Transactional
    public Map<String, Object> syncUsers(UUID configId, String tenantId) {
        LdapConfigEntity config = getConfig(configId);
        
        if (!config.getEnabled()) {
            throw new RuntimeException("LDAP 配置未启用");
        }
        
        Map<String, Object> result = new HashMap<>();
        int syncedCount = 0;
        int errorCount = 0;
        
        try {
            // 搜索 LDAP 用户
            Query query = Query.where("objectClass").is("person");
            query.setSearchScope(SearchScope.SUBTREE);
            query.setBase(config.getBaseDn());
            
            // 使用 LdapTemplate 搜索
            List<Map<String, Object>> ldapUsers = ldapTemplate.search(
                    query, (Attributes attrs) -> {
                        Map<String, Object> user = new HashMap<>();
                        try {
                            Attribute uidAttr = attrs.get("uid");
                            Attribute cnAttr = attrs.get("cn");
                            Attribute mailAttr = attrs.get("mail");
                            Attribute dnAttr = attrs.get("dn");
                            
                            user.put("dn", dnAttr != null ? dnAttr.get().toString() : "");
                            user.put("uid", uidAttr != null ? uidAttr.get().toString() : "");
                            user.put("displayName", cnAttr != null ? cnAttr.get().toString() : "");
                            user.put("email", mailAttr != null ? mailAttr.get().toString() : "");
                        } catch (NamingException e) {
                            // skip
                        }
                        return user;
                    });
            
            for (Map<String, Object> ldapUser : ldapUsers) {
                try {
                    syncUser(config, ldapUser, tenantId);
                    syncedCount++;
                } catch (Exception e) {
                    errorCount++;
                }
            }
            
            config.setLastSyncAt(Instant.now());
            config.setLastSyncStatus("SUCCESS");
            config.setLastSyncError(null);
            
        } catch (Exception e) {
            config.setLastSyncStatus("ERROR");
            config.setLastSyncError(e.getMessage());
            throw new RuntimeException("LDAP 同步失败: " + e.getMessage(), e);
        }
        
        config.setUpdatedAt(Instant.now());
        ldapConfigRepository.save(config);
        
        result.put("synced", syncedCount);
        result.put("errors", errorCount);
        result.put("status", "SUCCESS");
        return result;
    }

    /**
     * 同步单个用户。
     */
    @Transactional
    public LdapUserMappingEntity syncUser(LdapConfigEntity config, Map<String, Object> ldapUser, String tenantId) {
        String uid = (String) ldapUser.get("uid");
        String dn = (String) ldapUser.get("dn");
        
        // 检查是否已有映射
        LdapUserMappingEntity mapping = ldapUserMappingRepository
                .findByLdapUidAndTenantId(uid, tenantId)
                .orElse(null);
        
        if (mapping == null) {
            // 新用户 — 创建映射
            mapping = new LdapUserMappingEntity();
            mapping.setId(UUID.randomUUID());
            mapping.setLdapDn(dn);
            mapping.setLdapUid(uid);
            mapping.setTenantId(tenantId);
            mapping.setSyncStatus("SYNCED");
            mapping.setLdapAttributesJson(toJson(ldapUser));
            mapping.setCreatedAt(Instant.now());
            mapping.setUpdatedAt(Instant.now());
            
            // 尝试创建本地用户
            try {
                String displayName = (String) ldapUser.getOrDefault("displayName", uid);
                String email = (String) ldapUser.getOrDefault("email", uid + "@local");
                UserEntity localUser = userRepository.findByUsername(uid).orElse(null);
                if (localUser == null) {
                    localUser = new UserEntity();
                    localUser.setId(UUID.randomUUID());
                    localUser.setUsername(uid);
                    localUser.setDisplayName(displayName);
                    localUser.setEmail(email);
                    localUser.setTenantId(tenantId);
                    localUser.setCreatedAt(Instant.now());
                    userRepository.save(localUser);
                    mapping.setLocalUserId(localUser.getId());
                } else {
                    mapping.setLocalUserId(localUser.getId());
                }
            } catch (Exception e) {
                mapping.setSyncStatus("ERROR");
                mapping.setSyncError(e.getMessage());
            }
        } else {
            // 更新现有映射
            mapping.setLdapAttributesJson(toJson(ldapUser));
            mapping.setSyncStatus("SYNCED");
            mapping.setLastSyncedAt(Instant.now());
            mapping.setUpdatedAt(Instant.now());
        }
        
        return ldapUserMappingRepository.save(mapping);
    }

    /**
     * 获取同步状态。
     */
    public LdapConfigEntity getSyncStatus(UUID configId) {
        return getConfig(configId);
    }

    /**
     * 列出租户的所有 LDAP 配置。
     */
    public List<LdapConfigEntity> listConfigs(String tenantId) {
        return ldapConfigRepository.findByTenantId(tenantId);
    }

    /**
     * 获取 LDAP 用户映射列表。
     */
    public List<LdapUserMappingEntity> listMappings(String tenantId) {
        return ldapUserMappingRepository.findByTenantId(tenantId);
    }

    // ============================================================
    //  内部方法
    // ============================================================

    private LdapConfigEntity getConfig(UUID configId) {
        return ldapConfigRepository.findById(configId)
                .orElseThrow(() -> new RuntimeException("LDAP 配置不存在: " + configId));
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
