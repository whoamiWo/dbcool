package com.nocobase.integration.feishu;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserStatus;
import com.nocobase.auth.UserStatusService;
import com.nocobase.org.OrgUnitEntity;
import com.nocobase.org.OrgUnitService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 飞书组织架构同步（E1，D3）。
 *
 * <p>与 {@code DingTalkOrgSyncService} 同构：
 * <ul>
 *   <li>部门写入 {@code org_unit}：{@code source=FEISHU}、
 *       {@code external_id=open_department_id}，{@code path} 由
 *       {@link OrgUnitService#upsertExternal} 计算</li>
 *   <li>用户 username = {@code fs_<open_id>}，已存在用户逐字段更新</li>
 *   <li>本轮未出现的飞书来源用户 → {@code RESIGNED + enabled=false} 并吊销会话</li>
 *   <li>单部门/单用户失败计入 {@code usersFailed}，{@code > 0} 时 {@code code != 0}</li>
 * </ul>
 *
 * <p>开关 {@code feishu.sync.enabled} 默认关闭；关闭时直接返回错误，
 * <b>不会发生任何外部 HTTP 请求</b>。
 */
@Service
public class FeishuOrgSyncService {

    private static final Logger log = LoggerFactory.getLogger(FeishuOrgSyncService.class);

    static final String SOURCE = "FEISHU";
    private static final String USERNAME_PREFIX = "fs_";
    private static final String ROOT_DEPARTMENT_ID = "0";

    private final FeishuAppService appService;
    private final UserRepository userRepository;
    private final UserProvisioningService provisioningService;
    private final OrgUnitService orgUnitService;
    private final UserStatusService userStatusService;

    @Value("${feishu.sync.enabled:false}")
    private boolean syncEnabled;

    /** 最近一次同步结果（进程内，供 /sync-status 读取）。 */
    private final Map<String, Map<String, Object>> lastResults = new java.util.concurrent.ConcurrentHashMap<>();

    public FeishuOrgSyncService(FeishuAppService appService,
                                UserRepository userRepository,
                                UserProvisioningService provisioningService,
                                OrgUnitService orgUnitService,
                                UserStatusService userStatusService) {
        this.appService = appService;
        this.userRepository = userRepository;
        this.provisioningService = provisioningService;
        this.orgUnitService = orgUnitService;
        this.userStatusService = userStatusService;
    }

    boolean isSyncEnabled() {
        return syncEnabled;
    }

    /**
     * 手动触发组织架构同步。
     *
     * @return 同步结果；{@code usersFailed > 0} 时 {@code code != 0}
     */
    public Map<String, Object> syncOrganization(String tenantId) {
        if (!syncEnabled) {
            return Map.of("code", 500,
                    "message", "飞书组织同步未启用 (feishu.sync.enabled=false)",
                    "data", emptyStats());
        }
        if (!appService.isConfigured(tenantId)) {
            return Map.of("code", 500,
                    "message", "飞书应用未配置 (feishu.app-id / app-secret)",
                    "data", emptyStats());
        }
        SyncStats stats = new SyncStats();
        try {
            log.info("[Feishu] 开始同步组织架构，tenantId={}", tenantId);

            // 1) 部门树（BFS 已保证父在子前）
            List<Map<String, Object>> departments = appService.listDepartments(tenantId);
            stats.departmentsSynced = departments.size();
            Map<String, UUID> localDeptIds = upsertDepartments(tenantId, departments, stats);

            // 2) 逐部门拉成员（含根部门 0 的直属成员）
            Set<String> seenOpenIds = new HashSet<>();
            Set<String> deptIdsToScan = new LinkedHashSet<>();
            deptIdsToScan.add(ROOT_DEPARTMENT_ID);
            deptIdsToScan.addAll(localDeptIds.keySet());
            for (String deptId : deptIdsToScan) {
                syncDepartmentUsers(tenantId, deptId, localDeptIds, seenOpenIds, stats);
            }

            // 3) 离职：本轮未出现的飞书来源用户
            markResigned(tenantId, seenOpenIds, stats);

            log.info("[Feishu] 组织架构同步完成 tenant={} depts={} users={} failed={} resigned={}",
                    tenantId, stats.departmentsSynced, stats.usersSynced,
                    stats.usersFailed, stats.usersResigned);

            Map<String, Object> data = stats.toMap();
            lastResults.put(tenantId, data);
            boolean ok = stats.usersFailed == 0 && stats.errors.isEmpty();
            return Map.of("code", ok ? 0 : 502,
                    "message", ok ? "success" : "部分数据同步失败，详见 data.errors",
                    "data", data);
        } catch (Exception e) {
            log.error("[Feishu] 组织架构同步失败 tenant={}: {}", tenantId, e.getMessage(), e);
            stats.errors.add("fatal: " + e.getMessage());
            stats.usersFailed += 1;
            Map<String, Object> data = stats.toMap();
            lastResults.put(tenantId, data);
            return Map.of("code", 502,
                    "message", "同步失败：" + e.getMessage(),
                    "data", data);
        }
    }

    /** 供 {@code GET /api/feishu/sync-status} 读取最近一次结果。 */
    public Map<String, Object> getLastSyncResult(String tenantId) {
        return lastResults.get(tenantId);
    }

    private Map<String, UUID> upsertDepartments(String tenantId,
                                                List<Map<String, Object>> departments,
                                                SyncStats stats) {
        Map<String, UUID> mapping = new LinkedHashMap<>();
        for (Map<String, Object> dept : departments) {
            String externalId = String.valueOf(dept.get("id"));
            Object parent = dept.get("parentId");
            String parentExt = (parent == null || ROOT_DEPARTMENT_ID.equals(String.valueOf(parent)))
                    ? null : String.valueOf(parent);
            try {
                OrgUnitEntity unit = orgUnitService.upsertExternal(
                        tenantId, SOURCE, externalId,
                        String.valueOf(dept.get("name")), parentExt,
                        dept.get("order") instanceof Number n ? n.intValue() : 0);
                mapping.put(externalId, unit.getId());
            } catch (Exception e) {
                stats.errors.add("department " + externalId + ": " + e.getMessage());
                stats.usersFailed += 1;
            }
        }
        return mapping;
    }

    private void syncDepartmentUsers(String tenantId, String deptId,
                                     Map<String, UUID> localDeptIds, Set<String> seen,
                                     SyncStats stats) {
        try {
            List<Map<String, Object>> users = appService.listDepartmentUsers(tenantId, deptId);
            for (Map<String, Object> user : users) {
                syncOneUser(user, tenantId, deptId, localDeptIds, seen, stats);
            }
        } catch (Exception e) {
            log.warn("[Feishu] 部门 {} 成员同步失败: {}", deptId, e.getMessage());
            stats.errors.add("department " + deptId + " users: " + e.getMessage());
            stats.usersFailed += 1;
        }
    }

    private void syncOneUser(Map<String, Object> user, String tenantId, String deptId,
                             Map<String, UUID> localDeptIds, Set<String> seen, SyncStats stats) {
        String openId = String.valueOf(user.getOrDefault("openId", ""));
        if (openId.isBlank()) return;
        if (!seen.add(openId)) return; // 一人多部门去重
        try {
            String username = USERNAME_PREFIX + openId;
            String name = String.valueOf(user.getOrDefault("name", ""));
            UserEntity entity = userRepository.findByUsername(username).orElse(null);
            boolean created = false;
            if (entity == null) {
                entity = provisioningService.createExternal(
                        tenantId, username, name,
                        nullIfBlank(String.valueOf(user.getOrDefault("email", ""))), SOURCE);
                created = true;
            }
            if (!name.isBlank()) entity.setDisplayName(name);
            String email = String.valueOf(user.getOrDefault("email", ""));
            if (!email.isBlank()) entity.setEmail(email);
            String mobile = String.valueOf(user.getOrDefault("mobile", ""));
            if (!mobile.isBlank()) entity.setPhone(mobile);
            String jobTitle = String.valueOf(user.getOrDefault("jobTitle", ""));
            if (!jobTitle.isBlank()) entity.setPosition(jobTitle);

            String leader = String.valueOf(user.getOrDefault("leaderUserId", ""));
            if (!leader.isBlank()) {
                UserEntity target = entity;
                userRepository.findByUsername(USERNAME_PREFIX + leader)
                        .ifPresent(m -> target.setManagerId(m.getId()));
            }

            UUID localDept = resolveDept(user, deptId, localDeptIds);
            if (localDept != null) entity.setDepartmentId(localDept);

            entity.setStatus(UserStatus.ACTIVE);
            entity.setEnabled(true);
            if (created) entity.setCreatedAt(Instant.now());
            userRepository.save(entity);
            stats.usersSynced++;
        } catch (Exception e) {
            log.warn("[Feishu] 用户 {} 同步失败: {}", openId, e.getMessage());
            stats.errors.add("user " + openId + ": " + e.getMessage());
            stats.usersFailed++;
        }
    }

    /** 优先取 department_ids 中能映射到本地部门的第一项，否则用当前遍历部门。 */
    @SuppressWarnings("unchecked")
    private UUID resolveDept(Map<String, Object> user, String iteratingDeptId,
                             Map<String, UUID> localDeptIds) {
        Object ids = user.get("departmentIds");
        if (ids instanceof List<?> list) {
            for (Object d : list) {
                UUID local = localDeptIds.get(String.valueOf(d));
                if (local != null) return local;
            }
        }
        if (ROOT_DEPARTMENT_ID.equals(iteratingDeptId)) return null;
        return localDeptIds.get(iteratingDeptId);
    }

    private void markResigned(String tenantId, Set<String> seenOpenIds, SyncStats stats) {
        List<UserEntity> users = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        for (UserEntity u : users) {
            if (u.getUsername() == null || !u.getUsername().startsWith(USERNAME_PREFIX)) continue;
            String openId = u.getUsername().substring(USERNAME_PREFIX.length());
            if (seenOpenIds.contains(openId)) continue;
            if (UserStatus.RESIGNED.equals(u.getStatus()) && !u.isEnabled()) continue;
            u.setStatus(UserStatus.RESIGNED);
            u.setEnabled(false);
            userRepository.save(u);
            userStatusService.invalidate(u.getId());
            stats.usersResigned++;
        }
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("departmentsSynced", 0);
        m.put("usersSynced", 0);
        m.put("usersFailed", 0);
        m.put("usersResigned", 0);
        m.put("errors", List.of());
        return m;
    }

    /** 同步统计（字段名与钉钉 SyncStats 对齐，便于前端复用）。 */
    static final class SyncStats {
        int departmentsSynced;
        int usersSynced;
        int usersFailed;
        int usersResigned;
        final List<String> errors = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("departmentsSynced", departmentsSynced);
            m.put("usersSynced", usersSynced);
            m.put("usersFailed", usersFailed);
            m.put("usersResigned", usersResigned);
            m.put("errors", List.copyOf(errors));
            m.put("timestamp", System.currentTimeMillis());
            return m;
        }
    }
}
