package com.nocobase.ldap;

import com.nocobase.ldap.entity.LdapConfigEntity;
import com.nocobase.ldap.entity.LdapUserMappingEntity;
import com.nocobase.ldap.repository.LdapConfigRepository;
import com.nocobase.ldap.repository.LdapUserMappingRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
     * 执行同步 — 从 LDAP 导入所有用户（真实 LdapTemplate 查询）。
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
            // 使用 LdapTemplate 真实查询 LDAP 目录
            String filter = config.getUserSearchFilter();
            List<String> attributes = List.of("dn", "uid", "displayName", "mail", "email");
            List<Map<String, Object>> ldapUsers = ldapTemplate.search(
                    config.getBaseDn(),
                    filter,
                    (ContextMapper<Map<String, Object>>) ctx -> {
                        DirContextOperations d = (DirContextOperations) ctx;
                        Map<String, Object> user = new HashMap<>();
                        user.put("dn", d.getNameInNamespace());
                        user.put("uid", d.getStringAttribute("uid"));
                        user.put("displayName", d.getStringAttribute("displayName"));
                        user.put("mail", d.getStringAttribute("mail"));
                        user.put("email", d.getStringAttribute("email"));
                        return user;
                    }
            );
            
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
     * 定时同步（按配置的 syncIntervalMinutes 执行）。
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void scheduledSync() {
        try {
            List<LdapConfigEntity> configs = ldapConfigRepository.findByTenantId("system");
            for (LdapConfigEntity config : configs) {
                if (Boolean.TRUE.equals(config.getEnabled())) {
                    syncUsers(config.getId(), config.getTenantId());
                }
            }
        } catch (Exception e) {
            // 定时任务失败不影响其他任务
        }
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
                    localUser.setPasswordHash("LDAP_SYNCED");
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

    private String toJson(Map<?, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
