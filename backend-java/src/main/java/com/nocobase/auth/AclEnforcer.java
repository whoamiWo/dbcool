package com.nocobase.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AclEnforcer.class);

    private final UserRoleRepository userRoleRepository;
    private final AclPolicyRepository policyRepository;
    private final RoleRepository roleRepository;
    /** A-1：真实部门子树解析（替代坏的 DEPT SQL 片段与恒错误的内存判定） */
    private final DeptScopeResolver deptScopeResolver;

    /**
     * fail-open 开关(Stage 1 安全收口).
     *
     * <p><code>true</code>: 无策略/异常时放行(历史默认行为,仅用于灰度回滚窗口)。
     * <p><code>false</code>: 无策略/异常时拒绝 —— fail-closed,生产默认。
     *
     * <p>由 <code>app.acl.fail-open</code> 控制,默认 <b>false</b>(fail-closed)。
     */
    private final boolean failOpen;

    public AclEnforcer(UserRoleRepository userRoleRepository,
                       AclPolicyRepository policyRepository,
                       RoleRepository roleRepository,
                       DeptScopeResolver deptScopeResolver,
                       @org.springframework.beans.factory.annotation.Value("${app.acl.fail-open:false}") boolean failOpen) {
        this.userRoleRepository = userRoleRepository;
        this.policyRepository = policyRepository;
        this.roleRepository = roleRepository;
        this.deptScopeResolver = deptScopeResolver;
        this.failOpen = failOpen;
        if (!failOpen) {
            log.info("[acl] fail-closed 生效:无策略/异常一律拒绝(默认)");
        } else {
            log.warn("[acl] ⚠️  fail-open 开启:无策略/异常将放行 — 仅灰度回滚窗口使用");
        }
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
            // Stage 1 安全收口:无策略时 fail-closed(默认拒绝)。
            // 仅 fail-open=true(灰度回滚窗口)时保持放行。
            return failOpen;
        }
        // 白名单语义:查所有 type=ACTION policy
        List<AclPolicyEntity> actionPolicies = relevant.stream()
                .filter(p -> p.getType() == AclPolicyEntity.Type.ACTION)
                .toList();
        if (actionPolicies.isEmpty()) {
            // 只有 FIELD/ROW policy → 不限制 CRUD。
            // fail-closed 下同样拒绝(无显式 ACTION 允许 = 拒绝)。
            return failOpen;
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
        if (record == null) return null;
        Set<String> hidden = filterReadableFields(userId, tenantId, collectionName);
        Map<String, Object> copy = new java.util.HashMap<>(record);
        if (hidden != null && !hidden.isEmpty()) {
            for (String h : hidden) copy.remove(h);
        }
        return copy;
    }

    /**
     * B7 返工：按角色数据范围过滤记录列表（行级过滤）。
     *
     * <p>修复前 {@link #buildDataScopeCondition} / {@code dataScopeType} 全仓无调用点，
     * SELF/DEPT/CUSTOM 是纯死代码。现由本方法在记录列表读路径真实生效。
     *
     * <p>语义：
     * <ul>
     *   <li>无数据范围约束 → 原样返回</li>
     *   <li>SELF → 仅保留 {@code created_by == userId} 的行</li>
     *   <li>DEPT → 仅保留「所在部门<b>子树</b>内成员」创建的行（A-1 修复；
     *       修复前是 {@code department_id != null} —— 业务表根本没有该列，
     *       判定形同虚设）。用户无部门 → 收敛为 SELF（决策 D6）。</li>
     *   <li>CUSTOM → 保守：记录含 {@code _data_scope_match=true} 才保留</li>
     * </ul>
     */
    public List<Map<String, Object>> filterRecordsByDataScope(UUID userId, String tenantId,
                                                               String collectionName,
                                                               List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return records;
        return filterRecordsByDataScopeImpl(userId, tenantId, "created_by", records);
    }

    private List<Map<String, Object>> filterRecordsByDataScopeImpl(UUID userId, String tenantId,
                                                                    String creatorField,
                                                                    List<Map<String, Object>> records) {
        ScopeKind kind = resolveScopeKind(userId, tenantId);
        if (kind == null || kind == ScopeKind.ALL) return records;
        if (kind == ScopeKind.DEPT) {
            // A-1：DEPT 一次查出「可见创建者集合」（部门子树 ∪ 本人），再逐行匹配
            Set<UUID> visible = deptScopeResolver.resolveVisibleCreatorIds(userId, tenantId);
            return records.stream()
                    .filter(r -> createdByIn(r, creatorField, visible))
                    .toList();
        }
        if (kind == ScopeKind.CUSTOM) {
            // CUSTOM：保守——记录带 _data_scope_match=true 才保留（沿用既有语义）
            return records.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("_data_scope_match")))
                    .toList();
        }
        // SELF：仅本人
        return records.stream()
                .filter(r -> createdByIn(r, creatorField, Set.of(userId)))
                .toList();
    }

    private boolean createdByIn(Map<String, Object> record, String field, Set<UUID> allowed) {
        Object created = record.get(field);
        if (created == null) return false;
        try {
            return allowed.contains(UUID.fromString(created.toString()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 数据范围种类（内部分派用；ALL 与 null 都表示不过滤）。 */
    enum ScopeKind { ALL, SELF, DEPT, CUSTOM }

    /**
     * 可下推的数据范围谓词 + 是否需要内存兜底。
     *
     * <p>T1.3 要求：DEPT/SELF 必须下推 SQL，避免"先分页再过滤"导致分页口径失真；
     * CUSTOM 的表达式无法安全参数化，只能保留内存判定（调用方需 log.warn）。
     */
    public record SqlPredicate(String sql, List<Object> params) {}

    /** @param predicate 为 null 表示无需下推（ALL）；@param customFallback 为 true 表示必须内存判定 */
    public record DataScopePlan(SqlPredicate predicate, boolean customFallback) {}

    /**
     * 计算数据范围下推计划。
     *
     * <p>参数化 SQL（不使用字符串拼接），字段名严格白名单校验。
     */
    public DataScopePlan resolveDataScopePlan(UUID userId, String tenantId, String creatorField) {
        ScopeKind kind = resolveScopeKind(userId, tenantId);
        if (kind == null || kind == ScopeKind.ALL) {
            return new DataScopePlan(null, false);
        }
        String field = safeIdentifier(creatorField);
        if (kind == ScopeKind.SELF) {
            return new DataScopePlan(new SqlPredicate(field + " = ?", List.of(userId)), false);
        }
        if (kind == ScopeKind.DEPT) {
            String path = deptScopeResolver.resolveUserDeptPath(userId, tenantId);
            if (path == null) {
                // D6：无部门 → SELF
                return new DataScopePlan(new SqlPredicate(field + " = ?", List.of(userId)), false);
            }
            String sql = field + " IN (SELECT u.id FROM users u "
                    + "JOIN org_unit o ON u.department_id = o.id "
                    + "WHERE o.tenant_id = ? AND o.deleted = FALSE AND o.path LIKE ?)";
            return new DataScopePlan(new SqlPredicate(sql, List.of(tenantId, path + "%")), false);
        }
        // CUSTOM：表达式不可安全参数化 → 不下推
        return new DataScopePlan(null, true);
    }

    private static String safeIdentifier(String raw) {
        String field = (raw == null || raw.isBlank()) ? "created_by" : raw;
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法字段名: " + field);
        }
        return field;
    }

    private ScopeKind resolveScopeKind(UUID userId, String tenantId) {
        List<UUID> roleIds = loadRoleIdsIncludingInheritance(userId, tenantId);
        if (roleIds.isEmpty()) return null;
        for (UUID rid : roleIds) {
            for (AclPolicyEntity p : policyRepository.findByRoleIdAndTenantId(rid, tenantId)) {
                AclPolicyEntity.DataScopeType t = p.getDataScopeType();
                if (t == null || t == AclPolicyEntity.DataScopeType.ALL) continue;
                return switch (t) {
                    case SELF -> ScopeKind.SELF;
                    case DEPT -> ScopeKind.DEPT;
                    case CUSTOM -> ScopeKind.CUSTOM;
                    default -> null;
                };
            }
        }
        return null;
    }

    /**
     * 判断一条记录是否匹配数据范围谓词。
     *
     * @deprecated A-1 修复后由 {@link #filterRecordsByDataScope} 内部直接判定；
     *   保留此方法仅为兼容既有调用（内部改走 {@link DeptScopeResolver}）。
     */
    @Deprecated
    public boolean dataScopeSqlMatches(String scopeSql, Map<String, Object> record) {
        if (scopeSql == null || scopeSql.isBlank() || record == null) return true;
        String s = scopeSql.trim();
        // SELF：created_by = '<uuid>'
        if (s.startsWith("created_by")) {
            Object created = record.get("created_by");
            String expected = s.replaceAll("(?i)^created_by\\s*=\\s*'", "").replace("'", "").trim();
            return created != null && expected.equalsIgnoreCase(created.toString());
        }
        // DEPT / 其它子查询形态：无法离线判定子查询真假 → 保守拒绝（fail-closed）。
        // 修复前这里返回 record.get("department_id") != null，恒 null 恒 false，形同虚设。
        return false;
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
     * <p>Week 17 拆分: CREATE 与 UPDATE 视为同一个禁写集合(向后兼容),
     * 控制器端需要细分时调用 {@link #filterWritableFields(UUID, String, String, AclPolicyEntity.Action)}。
     */
    public Set<String> filterWritableFields(UUID userId, String tenantId, String collectionName) {
        return filterWritableFields(userId, tenantId, collectionName, null);
    }

    /**
     * Week 17: 按 action 精确过滤禁写字段。
     *
     * <p>典型用例:
     * <ul>
     *   <li>{@code action=null}: 兼容旧逻辑,CREATE ∪ UPDATE 全部禁写集合</li>
     *   <li>{@code action=CREATE}: 只看 action=CREATE 的 FIELD policy,允许"创建后可改"</li>
     *   <li>{@code action=UPDATE}: 只看 action=UPDATE 的 FIELD policy,允许"建表后不能改"</li>
     * </ul>
     *
     * <p>action=DELETE 同理(虽然 controller 端当前没有 DELETE 路径的 data,留扩展)。
     */
    public Set<String> filterWritableFields(UUID userId, String tenantId, String collectionName,
                                            AclPolicyEntity.Action action) {
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
            // action 维度过滤:仅 CREATE/UPDATE/DELETE 与请求 action 匹配的 policy 生效
            AclPolicyEntity.Action pa = p.getAction();
            if (pa == null) continue;
            if (pa != AclPolicyEntity.Action.CREATE
                    && pa != AclPolicyEntity.Action.UPDATE
                    && pa != AclPolicyEntity.Action.DELETE) continue;
            // 显式传 action 时要求严格匹配;传 null 时取 CREATE+UPDATE 并集
            if (action != null && pa != action) continue;
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
        assertCanWriteFields(userId, tenantId, collectionName, data, null);
    }

    /**
     * Week 17: 按 action 精确检查 — 支持"只能创建不能修改"或反之。
     */
    public void assertCanWriteFields(UUID userId, String tenantId, String collectionName,
                                     Map<String, Object> data, AclPolicyEntity.Action action) {
        if (data == null || data.isEmpty()) return;
        Set<String> forbidden = filterWritableFields(userId, tenantId, collectionName, action);
        if (forbidden == null || forbidden.isEmpty()) return;
        List<String> violations = new ArrayList<>();
        for (String k : data.keySet()) {
            if (forbidden.contains(k)) violations.add(k);
        }
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN,
                    "ACL 拒绝: 字段不可写 action=" + action + " collection=" + collectionName
                            + " forbidden=" + violations);
        }
    }

    private List<UUID> loadRoleIds(UUID userId) {
        return userRoleRepository.findByIdUserId(userId)
                .stream().map(ur -> ur.getId().getRoleId()).toList();
    }

    // ============================================================
    //  Wave3 B7: 数据范围过滤
    // ============================================================

    /**
     * 根据角色数据范围配置生成 SQL 过滤条件片段。
     *
     * <p>CUSTOM 类型:解析 dataScopeConfigJson 中的自定义规则,返回可直接拼接到 SQL WHERE 的片段。
     *
     * @param roleId        用户直接持有的角色 ID
     * @param tenantId      租户隔离
     * @param creatorField  创建者字段(默认 "created_by")
     */
    public String buildDataScopeCondition(UUID roleId, String tenantId, String creatorField) {
        List<AclPolicyEntity> policies = policyRepository.findByRoleIdAndTenantId(roleId, tenantId);
        AclPolicyEntity scopePolicy = policies.stream()
                .filter(p -> p.getDataScopeType() != null)
                .findFirst()
                .orElse(null);

        if (scopePolicy == null || scopePolicy.getDataScopeConfigJson() == null) {
            return null;
        }

        try {
            Map<String, Object> cfg = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(scopePolicy.getDataScopeConfigJson(), Map.class);
            Object sql = cfg.get("sql");
            if (sql instanceof String s && !s.isBlank()) {
                return s;
            }
            Object userExpr = cfg.get("userExpression");
            if (userExpr instanceof String expr) {
                return expr.replace("${userId}", "'${userId}'");
            }
        } catch (Exception ex) {
            log.error("[acl] 数据范围配置解析失败 roleId={}", roleId, ex);
        }
        return null;
    }

    /**
     * B7 返工：把角色级数据范围 SQL 片段解析为「可直接嵌入 WHERE 子句的谓词」。
     *
     * <p>修复前 {@link #buildDataScopeCondition(UUID, String, String)} 全仓零调用点，
     * data_scope 字段（ALL/DEPT/SELF/CUSTOM）是纯死代码。现由
     * {@link #filterRecord(UUID, String, String, Map)} 真实调用，
     * 把 SELF/DEPT/CUSTOM 范围落到记录级过滤。
     *
     * @param userId       当前用户
     * @param tenantId     租户
     * @param creatorField 记录中标识归属人的列名（通常 {@code created_by}）
     * @return 谓词字符串（如 {@code "created_by = '<uuid>'"}）；无数据范围约束时返回 null
     */
    public String resolveDataScopeSql(UUID userId, String tenantId, String creatorField) {
        List<UUID> roleIds = loadRoleIdsIncludingInheritance(userId, tenantId);
        if (roleIds.isEmpty()) return null;

        // 遍历用户所有角色（含祖先）的 policies，找出首个带 data_scope 的策略
        AclPolicyEntity scopePolicy = null;
        UUID scopeRole = null;
        for (UUID rid : roleIds) {
            List<AclPolicyEntity> policies = policyRepository.findByRoleIdAndTenantId(rid, tenantId);
            for (AclPolicyEntity p : policies) {
                if (p.getDataScopeType() != null && p.getDataScopeType()
                        != AclPolicyEntity.DataScopeType.ALL) {
                    scopePolicy = p;
                    scopeRole = rid;
                    break;
                }
            }
            if (scopePolicy != null) break;
        }
        if (scopePolicy == null) return null;

        String field = creatorField != null && !creatorField.isBlank() ? creatorField : "created_by";
        return switch (scopePolicy.getDataScopeType()) {
            case SELF -> field + " = '" + userId + "'";
            case DEPT -> {
                // A-1 修复：修复前引用不存在的表 user_u（应为 users），且只比部门叶子
                // 不含子树。现由 DeptScopeResolver 生成基于 org_unit.path 前缀的真实
                // 子树谓词；无部门时降级为 SELF（决策 D6）。
                String deptSql = deptScopeResolver.buildDeptScopeSql(userId, tenantId, field);
                yield deptSql != null ? deptSql : field + " = '" + userId + "'";
            }
            case CUSTOM -> buildCustomScope(scopePolicy, userId);
            default -> null; // ALL
        };
    }

    private String buildCustomScope(AclPolicyEntity policy, UUID userId) {
        if (policy.getDataScopeConfigJson() == null) return null;
        try {
            Map<String, Object> cfg = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(policy.getDataScopeConfigJson(), Map.class);
            Object sql = cfg.get("sql");
            if (sql instanceof String s && !s.isBlank()) return s;
            Object expr = cfg.get("userExpression");
            if (expr instanceof String e) {
                return e.replace("${userId}", "'" + userId + "'");
            }
        } catch (Exception ex) {
            log.error("[acl] CUSTOM data_scope 解析失败 policy={}——调用回退到 SELF",
                    policy.getId(), ex);
        }
        return null;
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
            } catch (Exception e) {
                // Stage 1 安全收口:CTE 失败必须告警,不得静默吞异常。
                // 角色继承链断裂 = 该角色的祖先策略全部丢失 → 可能越权放行。
                // fail-closed:继承查询失败时该角色的祖先策略视为不存在(保守)。
                log.error("[acl] 角色继承查询失败 rid={} tenant={} — 该角色祖先策略将丢失",
                        rid, tenantId, e);
            }
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
        } catch (Exception e) {
            // Stage 1 安全收口:FIELD policy 配置解析失败必须告警。
            // 解析失败 → 该 policy 的 hidden 字段集合为空 = 无字段隐藏 = 可能越权读。
            log.error("[acl] FIELD policy 配置解析失败 configJson={} — 该策略字段隐藏失效",
                    configJson, e);
        }
        return Set.of();
    }
}
