package com.nocobase.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final RoleRepository roleRepository;

    public AclEnforcer(UserRoleRepository userRoleRepository,
                       AclPolicyRepository policyRepository,
                       RoleRepository roleRepository) {
        this.userRoleRepository = userRoleRepository;
        this.policyRepository = policyRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * 判断 user 是否能对 collectionName 执行 action(CREATE/READ/UPDATE/DELETE).
     * 返回 true 表示允许,false 表示拒绝.
     *
     * <p>US-308: 用户持有的 role 通过 parent_role_id 链继承祖先 role 的 ACL。
     * 因此实际生效的是「user 持有的角色」+「所有祖先角色」的并集。
     */
    public boolean isAllowed(UUID userId, String tenantId, String collectionName,
                             AclPolicyEntity.Action action) {
        List<UUID> roleIds = loadRoleIdsIncludingInheritance(userId, tenantId);
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
        List<UUID> roleIds = loadRoleIdsIncludingInheritance(userId, tenantId);
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

    /**
     * Week 16 US-FIELD-WRITE: 返回不可写字段名集合。
     *
     * <p>语义与 {@link #filterReadableFields} 对偶:
     * <ul>
     *   <li>无 FIELD policy on this action → 返回 null(表示全部可写)</li>
     *   <li>有 FIELD policy action=CREATE/UPDATE → cfg.hidden 是禁写字段</li>
     * </ul>
     *
     * <p>CREATE 与 UPDATE 视为同一个禁写集合(Week 16 MVP 范围);
     * 后续若需细分"只能创建不能修改"可加 action 维度过滤。
     */
    public Set<String> filterWritableFields(UUID userId, String tenantId, String collectionName) {
        List<UUID> roleIds = loadRoleIdsIncludingInheritance(userId, tenantId);
        List<AclPolicyEntity> policies = new ArrayList<>();
        for (UUID rid : roleIds) {
            policies.addAll(policyRepository.findByRoleIdAndTenantId(rid, tenantId));
        }
        Set<String> forbidden = new HashSet<>();
        boolean hasFieldPolicy = false;
        for (AclPolicyEntity p : policies) {
            if (!collectionName.equals(p.getSubject())) continue;
            if (p.getType() != AclPolicyEntity.Type.FIELD) continue;
            // CREATE 与 UPDATE 都视为禁写
            if (p.getAction() != AclPolicyEntity.Action.CREATE
                    && p.getAction() != AclPolicyEntity.Action.UPDATE) continue;
            hasFieldPolicy = true;
            forbidden.addAll(parseHidden(p.getConfigJson()));
        }
        return hasFieldPolicy ? forbidden : null;
    }

    /**
     * Week 16: 拒绝检查 — 若 data 中含任何禁写字段,抛 403。
     *
     * <p>语义:
     * <ul>
     *   <li>无 FIELD policy → 直接通过</li>
     *   <li>data 为 null/空 → 通过(没东西要写)</li>
     *   <li>data 任何 key 在禁写集合里 → 403 + 列出违规字段</li>
     * </ul>
     *
     * <p>NULL 值视为显式赋值(意图清空字段),同样禁止。
     */
    public void assertCanWriteFields(UUID userId, String tenantId, String collectionName,
                                     Map<String, Object> data) {
        if (data == null || data.isEmpty()) return;
        Set<String> forbidden = filterWritableFields(userId, tenantId, collectionName);
        if (forbidden == null || forbidden.isEmpty()) return;
        List<String> violations = new ArrayList<>();
        for (String k : data.keySet()) {
            if (forbidden.contains(k)) violations.add(k);
        }
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN,
                    "ACL 拒绝: 字段不可写 collection=" + collectionName
                            + " forbidden=" + violations);
        }
    }

    private List<UUID> loadRoleIds(UUID userId) {
        return userRoleRepository.findByIdUserId(userId)
                .stream().map(ur -> ur.getId().getRoleId()).toList();
    }

    /**
     * US-308: 加载 user 持有角色 + 所有祖先角色(通过 parent_role_id 链)。
     * 用 visited set 防自循环 + 用 CTE 一次 SQL 查全树。
     * 返回的列表去重。
     */
    public List<UUID> loadRoleIdsIncludingInheritance(UUID userId, String tenantId) {
        List<UUID> direct = loadRoleIds(userId);
        if (direct.isEmpty()) return List.of();
        Set<UUID> all = new LinkedHashSet<>(direct);
        for (UUID rid : direct) {
            try {
                List<UUID> ancestors = roleRepository.findSelfAndAncestors(rid, tenantId);
                all.addAll(ancestors);
            } catch (Exception ignored) { /* CTE 失败时不影响直接角色 */ }
        }
        return new ArrayList<>(all);
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
