package com.nocobase.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * ACL 强制服务(US-305/308).
 *
 * <p>根据用户的 role 加载 ACL policies,判断:
 * <ul>
 *   <li>{@link #canRead}/{@link #canCreate} — 是否允许操作某个 collection</li>
 *   <li>{@link #filterReadableFields} — 返回可以读的字段名集合(用于记录过滤)</li>
 *   <li>{@link #assertCan} — 推不掉的拒绝检查(403)</li>
 * </ul>
 *
 * <p>判定逻辑:
 * <pre>
 *   1. 加载用户所有 role 的 ACL policies(按 subject=collectionName)
 *   2. 无任何 policies → 默认允许(开放)
 *   3. 有 policies → 需至少有 1 条 type=ACTION policy action=READ/CREATE 才允许
 *   4. type=FIELD policy action=READ → cfg.config.hidden 是不可读字段
 * </pre>
 */
@Service
public class AclEnforcer {

    private final UserRoleRepository userRoleRepository;
    private final AclPolicyRepository policyRepository;

    public AclEnforcer(UserRoleRepository userRoleRepository, AclPolicyRepository policyRepository) {
        this.userRoleRepository = userRoleRepository;
        this.policyRepository = policyRepository;
    }

    /**
     * 判断 user 是否能对 collectionName 执行 action(CREATE/READ/UPDATE/DELETE).
     * 返回 true 表示允许,false 表示拒绝.
     */
    public boolean isAllowed(UUID userId, String tenantId, String collectionName,
                             AclPolicyEntity.Action action) {
        List<UUID> roleIds = loadRoleIds(userId);
        if (roleIds.isEmpty()) {
            // 无角色用户(通常不应该发生)→ 拒绝
            return false;
        }
        List<AclPolicyEntity> policies = new ArrayList<>();
        for (UUID rid : roleIds) {
            policies.addAll(policyRepository.findByRoleIdAndTenantId(rid, tenantId));
        }
        // 只有作用在该 collection 上的 policy 才生效
        List<AclPolicyEntity> relevant = policies.stream()
                .filter(p -> collectionName.equals(p.getSubject()))
                .toList();
        if (relevant.isEmpty()) {
            return true; // 无 policy 配置 → 默认允许
        }
        // 白名单语义:查所有 type=ACTION policy
        List<AclPolicyEntity> actionPolicies = relevant.stream()
                .filter(p -> p.getType() == AclPolicyEntity.Type.ACTION)
                .toList();
        if (actionPolicies.isEmpty()) {
            return true; // 只有 FIELD/ROW policy → 不限制 CRUD,默认允许
        }
        // 有 ACTION policy 需显式包含目标 action
        return actionPolicies.stream().anyMatch(p -> p.getAction() == action);
    }

    /**
     * 返回对 user 可见的字段名集合.
     * 没有 FIELD policy → 返回 null(表示全部可见).
     */
    public Set<String> filterReadableFields(UUID userId, String tenantId, String collectionName) {
        List<UUID> roleIds = loadRoleIds(userId);
        List<AclPolicyEntity> policies = new ArrayList<>();
        for (UUID rid : roleIds) {
            policies.addAll(policyRepository.findByRoleIdAndTenantId(rid, tenantId));
        }
        Set<String> hidden = new HashSet<>();
        boolean hasFieldPolicy = false;
        for (AclPolicyEntity p : policies) {
            if (!collectionName.equals(p.getSubject())) continue;
            if (p.getType() != AclPolicyEntity.Type.FIELD) continue;
            if (p.getAction() != AclPolicyEntity.Action.READ) continue;
            hasFieldPolicy = true;
            hidden.addAll(parseHidden(p.getConfigJson()));
        }
        return hasFieldPolicy ? hidden : null;
    }

    /**
     * 拒绝检查(拋 403).
     */
    public void assertCan(UUID userId, String tenantId, String collectionName,
                          AclPolicyEntity.Action action) {
        if (!isAllowed(userId, tenantId, collectionName, action)) {
            throw new ResponseStatusException(FORBIDDEN,
                    "ACL 拒绝: user=" + userId + " 不能 " + action + " collection=" + collectionName);
        }
    }

    /**
     * 过滤单条记录的字段(根据 FIELD policy 隐藏).
     */
    public Map<String, Object> filterRecord(UUID userId, String tenantId, String collectionName,
                                            Map<String, Object> record) {
        Set<String> hidden = filterReadableFields(userId, tenantId, collectionName);
        if (hidden == null || hidden.isEmpty() || record == null) return record;
        Map<String, Object> copy = new java.util.HashMap<>(record);
        for (String h : hidden) copy.remove(h);
        return copy;
    }

    private List<UUID> loadRoleIds(UUID userId) {
        return userRoleRepository.findByIdUserId(userId)
                .stream().map(ur -> ur.getId().getRoleId()).toList();
    }

    private Set<String> parseHidden(String configJson) {
        if (configJson == null || configJson.isBlank() || "{}".equals(configJson)) return Set.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = m.readValue(configJson, Map.class);
            Object hidden = cfg.get("hidden");
            if (hidden instanceof List<?> list) {
                Set<String> s = new HashSet<>();
                for (Object o : list) if (o != null) s.add(o.toString());
                return s;
            }
        } catch (Exception ignored) {}
        return Set.of();
    }
}
