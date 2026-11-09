package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserStatus;
import com.nocobase.auth.UserStatusService;
import com.nocobase.integration.common.OrgSyncSafety;
import com.nocobase.integration.credential.TenantIntegrationCredentialRepository;
import com.nocobase.integration.market.TenantIntegrationInstallRepository;
import com.nocobase.org.OrgUnitEntity;
import com.nocobase.org.OrgUnitService;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 钉钉组织架构同步服务 — 部门/用户列表拉取与定时同步。
 *
 * <p><b>A-4 修复（2026-11-04）</b>。修复前的四个静默缺陷：
 * <ol>
 *   <li>{@code syncAllUsers} 只拉 {@code dept_id=1}（根部门直属成员），
 *       多部门企业只同步到根部门的人。</li>
 *   <li>已存在用户直接 {@code return existing}，改名/调岗/换手机号永不更新。</li>
 *   <li>{@code scheduledSync} 硬编码 {@code "tenant_default"}，多租户只同步默认租户。</li>
 *   <li>{@code catch { log.warn; return count }} 把失败吞成"成功同步 N 人"，
 *       上层拿到 code=0 却什么都没写进去。</li>
 * </ol>
 *
 * <p>修复后的行为：按 {@link #collectAllDepartments} 的 BFS 结果<b>逐部门</b>拉成员；
 * 已存在用户逐字段更新；本轮未出现的钉钉来源用户标记 {@code RESIGNED + enabled=false}
 * 并失效其会话；定时任务遍历「已安装钉钉集成 / 已配置钉钉凭证」的租户；
 * 失败计数 {@code usersFailed} + {@code errors[]} 一并向上返回，非 0 即 {@code code != 0}。
 */
@Service
public class DingTalkOrgSyncService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkOrgSyncService.class);

    /** 外部来源标识；同时用于 org_unit.source 与 users 用户名前缀（dt_）。 */
    static final String SOURCE = "DINGTALK";
    private static final String USERNAME_PREFIX = "dt_";

    private final DingTalkAppService appService;
    private final UserRepository userRepository;
    private final UserMappingService userMappingService;
    private final UserProvisioningService provisioningService;
    private final OrgUnitService orgUnitService;
    private final UserStatusService userStatusService;
    private final TenantIntegrationInstallRepository installRepository;
    private final TenantIntegrationCredentialRepository credentialRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Wave4 待办池 #9：RestTemplate 提为可注入字段（此前内部 new，测试无法注入 mock，
     * 导致"分页是否真的拉全量"无法验证）。
     */
    private RestTemplate restTemplate = new RestTemplate();

    @Value("${dingtalk.app-key:}")
    private String appKey;

    @Value("${dingtalk.app-secret:}")
    private String appSecret;

    /**
     * G2：单租户存量部署的显式开关，默认关闭。
     *
     * <p>为 true 时才允许「租户未配置凭证 → 回退全局 app-key/secret」；多租户环境必须保持
     * false，否则未配置租户会拉取全局组织并写入自己的用户表。
     */
    @Value("${dingtalk.single-tenant-legacy:false}")
    private boolean singleTenantLegacy;

    /** 最近一次同步结果（进程内，供 /sync-status 读取；多副本部署时各副本各自一份）。 */
    private final Map<String, Map<String, Object>> lastResults = new java.util.concurrent.ConcurrentHashMap<>();

    public DingTalkOrgSyncService(DingTalkAppService appService,
                                  UserRepository userRepository,
                                  UserMappingService userMappingService,
                                  UserProvisioningService provisioningService,
                                  OrgUnitService orgUnitService,
                                  UserStatusService userStatusService,
                                  TenantIntegrationInstallRepository installRepository,
                                  TenantIntegrationCredentialRepository credentialRepository) {
        this.appService = appService;
        this.userRepository = userRepository;
        this.userMappingService = userMappingService;
        this.provisioningService = provisioningService;
        this.orgUnitService = orgUnitService;
        this.userStatusService = userStatusService;
        this.installRepository = installRepository;
        this.credentialRepository = credentialRepository;
    }

    /** 全局 {@code @Value} 凭证是否完整（仅 legacy 单租户路径使用）。 */
    private boolean isGlobalConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * 手动触发组织架构同步。
     *
     * @param tenantId 租户 ID
     * @return 同步结果统计；{@code usersFailed > 0} 时 {@code code != 0}
     */
    public Map<String, Object> syncOrganization(String tenantId) {
        return syncOrganization(tenantId, false, false);
    }

    /** 手动触发组织架构同步（dryRun：只返回待离职名单，不写库）。 */
    public Map<String, Object> syncOrganization(String tenantId, boolean dryRun) {
        return syncOrganization(tenantId, dryRun, false);
    }

    /**
     * 手动触发组织架构同步（G1/G2/G3）。
     *
     * <p>准入只认<b>租户级</b>凭证：未配置时直接拒绝且<b>不发生任何外部 HTTP</b>。
     * 单租户存量部署需显式开启 {@code dingtalk.single-tenant-legacy=true} 才允许全局回退。
     *
     * @param dryRun 只返回待离职名单，不修改用户状态、不吊销会话
     * @param force  跳过离职比例熔断（计划内大裁员；调用方必须校验 ADMIN 并写审计）
     */
    public Map<String, Object> syncOrganization(String tenantId, boolean dryRun, boolean force) {
        boolean tenantConfigured = appService.isTenantConfigured(tenantId);
        if (!tenantConfigured && !(singleTenantLegacy && isGlobalConfigured())) {
            return Map.of("code", 500,
                    "message", "该租户未配置钉钉应用凭证；已拒绝回退全局 token"
                            + "（单租户部署需显式开启 dingtalk.single-tenant-legacy=true）",
                    "data", emptyStats());
        }
        SyncStats stats = new SyncStats();
        stats.dryRun = dryRun;
        stats.force = force;
        try {
            log.info("[DingTalk] 开始同步组织架构，tenantId={} dryRun={} force={}",
                    tenantId, dryRun, force);
            String accessToken = appService.getAppAccessToken(tenantId);
            if ((accessToken == null || accessToken.isBlank()) && singleTenantLegacy && isGlobalConfigured()) {
                accessToken = getAccessToken();
            }
            if (accessToken == null || accessToken.isBlank()) {
                return Map.of("code", 500,
                        "message", "无法获取钉钉访问令牌（租户凭证无效或已过期）",
                        "data", emptyStats());
            }

            // 1) 部门树（BFS 已保证父在子前）
            List<Map<String, Object>> departments = collectAllDepartments(accessToken);
            stats.departmentsTotal = departments.size();
            Map<Long, UUID> localDeptIds = upsertDepartments(tenantId, departments, stats);

            // 2) 逐部门拉成员（含根部门 1 的直属成员）
            Set<String> seenDingTalkUserIds = new HashSet<>();
            Set<Long> deptIdsToScan = new LinkedHashSet<>();
            deptIdsToScan.add(1L);
            deptIdsToScan.addAll(localDeptIds.keySet());
            for (Long deptId : deptIdsToScan) {
                syncDepartmentUsers(accessToken, tenantId, deptId, localDeptIds, seenDingTalkUserIds, stats);
            }

            // 3) 离职：G1 只有全量部门/用户都成功时才允许判定，否则失败部门的成员会被误判。
            boolean complete = OrgSyncSafety.isComplete(stats.usersFailed, stats.errors.size(),
                    stats.departmentsSynced, stats.departmentsTotal);
            if (complete) {
                markResigned(tenantId, seenDingTalkUserIds, stats, force);
            } else {
                stats.resignSkipped = true;
                stats.resignSkipReason = "部分部门/用户同步失败，已跳过离职判定";
                log.warn("[DingTalk] 同步不完整（depts {}/{} failed={}），跳过离职判定 tenant={}",
                        stats.departmentsSynced, stats.departmentsTotal, stats.usersFailed, tenantId);
            }

            log.info("[DingTalk] 组织架构同步完成 tenant={} depts={}/{} users={} failed={} resigned={} dryRun={}",
                    tenantId, stats.departmentsSynced, stats.departmentsTotal, stats.usersSynced,
                    stats.usersFailed, stats.usersResigned, dryRun);

            Map<String, Object> data = stats.toMap();
            lastResults.put(tenantId, data);
            int code;
            String message;
            if (stats.resignBreakerTripped) {
                code = 409;
                message = "离职人数异常，已拒绝写入；请先以 dryRun=true 核对名单";
            } else if (!complete) {
                code = 502;
                message = "部分数据同步失败，详见 data.errors（已跳过离职判定）";
            } else if (stats.resignForced) {
                code = 0;
                message = "success(force)";
            } else {
                code = 0;
                message = dryRun ? "success(dryRun)" : "success";
            }
            return Map.of("code", code, "message", message, "data", data);

        } catch (Exception e) {
            log.error("[DingTalk] 组织架构同步失败 tenant={}: {}", tenantId, e.getMessage(), e);
            stats.errors.add("fatal: " + e.getMessage());
            stats.usersFailed += 1;
            Map<String, Object> data = stats.toMap();
            lastResults.put(tenantId, data);
            return Map.of("code", 502,
                    "message", "同步失败：" + e.getMessage(),
                    "data", data);
        }
    }

    /** 供 {@code GET /api/dingtalk/sync-status} 读取最近一次结果。 */
    public Map<String, Object> getLastSyncResult(String tenantId) {
        return lastResults.get(tenantId);
    }

    /**
     * 定时同步任务 - 每小时执行一次。
     *
     * <p>A-4：不再硬编码 {@code "tenant_default"}。遍历「已安装钉钉集成」或
     * 「已配置钉钉凭证」的租户；两者皆无时跳过并记录可操作日志。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledSync() {
        Set<String> tenants = new LinkedHashSet<>();
        try {
            installRepository.findByAppKey(SOURCE).stream()
                    .map(i -> i.getTenantId()).forEach(tenants::add);
        } catch (Exception e) {
            log.warn("[DingTalk] 读取已安装集成失败: {}", e.getMessage());
        }
        try {
            credentialRepository.findByProvider(SOURCE).stream()
                    .map(c -> c.getTenantId()).forEach(tenants::add);
        } catch (Exception e) {
            log.warn("[DingTalk] 读取钉钉凭证失败: {}", e.getMessage());
        }
        if (tenants.isEmpty()) {
            log.info("[DingTalk] 定时同步跳过：没有租户安装钉钉集成或配置钉钉凭证");
            return;
        }
        log.info("[DingTalk] 开始定时组织架构同步，租户数={}", tenants.size());
        for (String tenantId : tenants) {
            try {
                Map<String, Object> r = syncOrganization(tenantId);
                log.info("[DingTalk] 租户 {} 定时同步结果: code={}", tenantId, r.get("code"));
            } catch (Exception e) {
                log.error("[DingTalk] 租户 {} 定时同步失败: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    /**
     * 收集全部部门（Wave4 待办池 #9）。
     *
     * <p>钉钉 {@code /topapi/v2/dept/list} 返回的是**指定部门的直接子部门**，
     * 因此按部门树 BFS 递归拉取，并对每层做 cursor 分页。
     *
     * @param accessToken 钉钉 access_token
     * @return 全量部门列表（含根部门下的所有层级；按 BFS 顺序父在子前）
     */
    List<Map<String, Object>> collectAllDepartments(String accessToken) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(1L); // 根部门 id = 1

        int maxNodes = 2000; // 防御性上限：避免异常数据导致无限展开
        while (!queue.isEmpty() && all.size() < maxNodes) {
            long parentId = queue.poll();
            int cursor = 0;
            int size = 100;
            while (true) {
                String url = "https://oapi.dingtalk.com/topapi/v2/dept/list?"
                        + "access_token=" + accessToken
                        + "&parent_dept_id=" + parentId
                        + "&page_size=" + size
                        + "&cursor=" + cursor;
                String response = restTemplate.getForObject(url, String.class);
                JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
                int errcode = json.path("errcode").asInt(0);
                if (errcode != 0) {
                    throw new IllegalStateException("钉钉部门列表获取失败：errcode=" + errcode
                            + " errmsg=" + json.path("errmsg").asText("unknown"));
                }
                JsonNode result = json.path("result");
                JsonNode list = result.path("list");
                if (!list.isArray() || list.isEmpty()) {
                    break;
                }
                for (JsonNode dept : list) {
                    long deptId = dept.path("dept_id").asLong();
                    if (!visited.add(deptId)) {
                        continue;
                    }
                    Map<String, Object> deptMap = new HashMap<>();
                    deptMap.put("id", deptId);
                    deptMap.put("name", dept.path("name").asText());
                    deptMap.put("parentId", dept.path("parent_dept_id").asLong());
                    deptMap.put("order", dept.path("order").asInt());
                    all.add(deptMap);
                    queue.add(deptId);
                }
                boolean hasMore = result.path("has_more").asBoolean(false);
                long nextCursor = result.path("next_cursor").asLong(cursor + size);
                if (!hasMore || nextCursor <= cursor) {
                    break;
                }
                cursor = (int) nextCursor;
            }
        }
        log.info("[DingTalk] 部门收集完成，共 {} 个（含子部门层级）", all.size());
        return all;
    }

    /** 把钉钉部门写入 org_unit（source=DINGTALK，按 external_id 幂等）。 */
    private Map<Long, UUID> upsertDepartments(String tenantId, List<Map<String, Object>> departments,
                                              SyncStats stats) {
        Map<Long, UUID> mapping = new LinkedHashMap<>();
        for (Map<String, Object> dept : departments) {
            long externalId = ((Number) dept.get("id")).longValue();
            long parentExternalId = ((Number) dept.get("parentId")).longValue();
            String parentExt = parentExternalId == 1L ? null : String.valueOf(parentExternalId);
            try {
                OrgUnitEntity unit = orgUnitService.upsertExternal(
                        tenantId, SOURCE, String.valueOf(externalId),
                        String.valueOf(dept.get("name")), parentExt,
                        dept.get("order") instanceof Number n ? n.intValue() : 0);
                mapping.put(externalId, unit.getId());
                // G6：只统计成功 upsert 的部门，失败也显示全量会掩盖同步不完整。
                stats.departmentsSynced++;
            } catch (Exception e) {
                stats.errors.add("department " + externalId + ": " + e.getMessage());
                stats.usersFailed += 1;
            }
        }
        return mapping;
    }

    /** 拉取某部门成员并逐条落库（cursor 分页，全量）。 */
    private void syncDepartmentUsers(String accessToken, String tenantId, long deptId,
                                     Map<Long, UUID> localDeptIds, Set<String> seen,
                                     SyncStats stats) {
        try {
            int cursor = 0;
            int size = 100;
            int pages = 0;
            int maxPages = 500;
            while (pages < maxPages) {
                String url = "https://oapi.dingtalk.com/topapi/v2/user/list?"
                        + "access_token=" + accessToken
                        + "&dept_id=" + deptId
                        + "&cursor=" + cursor
                        + "&size=" + size;
                String response = restTemplate.getForObject(url, String.class);
                JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
                int errcode = json.path("errcode").asInt(0);
                if (errcode != 0) {
                    throw new IllegalStateException("钉钉用户列表获取失败 dept=" + deptId
                            + " errcode=" + errcode
                            + " errmsg=" + json.path("errmsg").asText("unknown"));
                }
                JsonNode result = json.path("result");
                JsonNode list = result.path("list");
                pages++;
                if (list.isArray()) {
                    for (JsonNode user : list) {
                        syncOneUser(user, tenantId, deptId, localDeptIds, seen, stats);
                    }
                }
                boolean hasMore = result.path("has_more").asBoolean(false);
                long nextCursor = result.path("next_cursor").asLong(cursor + size);
                if (!hasMore || list.isEmpty() || nextCursor <= cursor) {
                    break;
                }
                cursor = (int) nextCursor;
            }
        } catch (Exception e) {
            // A-4：不再静默吞掉。部门级失败意味着该部门全员可能漏同步，计入 usersFailed。
            log.warn("[DingTalk] 部门 {} 成员同步失败: {}", deptId, e.getMessage());
            stats.errors.add("department " + deptId + " users: " + e.getMessage());
            stats.usersFailed += 1;
        }
    }

    private void syncOneUser(JsonNode user, String tenantId, long deptId,
                             Map<Long, UUID> localDeptIds, Set<String> seen, SyncStats stats) {
        String dingtalkUserId = user.path("userid").asText("");
        if (dingtalkUserId.isBlank()) return;
        if (!seen.add(dingtalkUserId)) return; // 一人多部门去重
        String name = user.path("name").asText("");
        try {
            String username = USERNAME_PREFIX + dingtalkUserId;
            UserEntity entity = findExistingUser(tenantId, username, dingtalkUserId).orElse(null);
            boolean created = false;
            if (entity == null) {
                entity = provisioningService.createExternal(
                        tenantId, username, name, nullIfBlank(user.path("email").asText("")), SOURCE,
                        dingtalkUserId);
                created = true;
            }
            // A-4：已存在用户逐字段更新（修复前直接原样返回）
            if (name != null && !name.isBlank()) entity.setDisplayName(name);
            String email = user.path("email").asText("");
            if (!email.isBlank()) entity.setEmail(email);
            String mobile = user.path("mobile").asText("");
            if (!mobile.isBlank()) entity.setPhone(mobile);
            String title = user.path("title").asText("");
            if (!title.isBlank()) entity.setPosition(title);
            String employeeNo = user.path("job_number").asText("");
            if (!employeeNo.isBlank()) entity.setEmployeeNo(employeeNo);
            String managerDtId = user.path("manager_userid").asText("");
            if (!managerDtId.isBlank()) {
                UserEntity target = entity;
                findExistingUser(tenantId, USERNAME_PREFIX + managerDtId, managerDtId)
                        .ifPresent(m -> target.setManagerId(m.getId()));
            }
            java.util.UUID deptLocal = resolveDept(user, deptId, localDeptIds);
            if (deptLocal != null) entity.setDepartmentId(deptLocal);
            // 重新出现即视为在职（纠偏历史 RESIGNED）；F4：不覆盖管理员手工禁用。
            if (created || !entity.isManuallyDisabled()) {
                entity.setStatus(UserStatus.ACTIVE);
                entity.setEnabled(true);
                entity.setDisableReason(null);
            }
            entity.setExternalSource(SOURCE);
            entity.setExternalId(dingtalkUserId);
            if (created) entity.setCreatedAt(Instant.now());
            userRepository.save(entity);
            userMappingService.mapUser(dingtalkUserId, entity.getId().toString(), tenantId);
            stats.usersSynced++;
        } catch (Exception e) {
            // A-4：单用户失败不得中断整批，但必须计数并回传
            log.warn("[DingTalk] 用户 {} 同步失败: {}", dingtalkUserId, e.getMessage());
            stats.errors.add("user " + dingtalkUserId + ": " + e.getMessage());
            stats.usersFailed++;
        }
    }

    /** F2：租户 + 外部身份查找；仅在存量账号没有 external_id 时回退到租户内用户名。 */
    private java.util.Optional<UserEntity> findExistingUser(String tenantId, String username, String dingtalkUserId) {
        if (dingtalkUserId != null && !dingtalkUserId.isBlank()) {
            java.util.Optional<UserEntity> byExternal = userRepository
                    .findByTenantIdAndExternalSourceAndExternalId(tenantId, SOURCE, dingtalkUserId);
            if (byExternal.isPresent()) return byExternal;
        }
        return userRepository.findByTenantIdAndUsername(tenantId, username);
    }

    /** 优先使用 user.dept_id_list 中能映射到本地部门的第一项，否则用当前遍历部门。 */
    private UUID resolveDept(JsonNode user, long iteratingDeptId, Map<Long, UUID> localDeptIds) {
        JsonNode deptList = user.path("dept_id_list");
        if (deptList.isArray()) {
            for (JsonNode d : deptList) {
                UUID local = localDeptIds.get(d.asLong());
                if (local != null) return local;
            }
        }
        if (iteratingDeptId == 1L) return null; // 根部门无本地 org_unit 行
        return localDeptIds.get(iteratingDeptId);
    }

    /**
     * 离职处理（G1/G3）：仅在调用方确认「全量同步成功」后进入。
     *
     * <p>与飞书共用 {@link OrgSyncSafety}：dryRun 只返回名单；待离职超过比例阈值
     * 或 10 人时熔断（{@code code=409}）；计划内大裁员需显式 {@code force}。
     * 不删除用户（保留历史引用）。
     */
    private void markResigned(String tenantId, Set<String> seenDingTalkUserIds,
                              SyncStats stats, boolean force) {
        List<UserEntity> users = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<UserEntity> candidates = new ArrayList<>();
        for (UserEntity u : users) {
            if (u.getUsername() == null || !u.getUsername().startsWith(USERNAME_PREFIX)) continue;
            // 跨租户同名账号的用户名可能带租户后缀，真实 userid 以 external_id 为准。
            String dtId = SOURCE.equals(u.getExternalSource()) && u.getExternalId() != null
                    ? u.getExternalId()
                    : u.getUsername().substring(USERNAME_PREFIX.length());
            if (seenDingTalkUserIds.contains(dtId)) continue;
            if (u.isManuallyDisabled()) continue; // F4：不覆盖管理员手工禁用
            if (UserStatus.RESIGNED.equals(u.getStatus()) && !u.isEnabled()) continue;
            candidates.add(u);
        }
        if (candidates.isEmpty()) return;

        stats.pendingResignations = candidates.stream().map(UserEntity::getUsername).toList();
        int seen = seenDingTalkUserIds.size();
        OrgSyncSafety.Decision decision = OrgSyncSafety.evaluate(
                candidates.size(), seen, stats.dryRun, force);
        switch (decision) {
            case NONE -> {
                return;
            }
            case DRY_RUN -> {
                stats.resignSkipped = true;
                stats.resignSkipReason = "dryRun：仅返回名单，未写入";
                return;
            }
            case TRIPPED -> {
                stats.resignBreakerTripped = true;
                stats.resignSkipped = true;
                stats.resignSkipReason = OrgSyncSafety.breakerReason(candidates.size(), seen);
                log.error("[DingTalk] 离职比例熔断 tenant={} 待离职={} 已见={}", tenantId,
                        candidates.size(), seen);
                return;
            }
            case FORCED -> {
                stats.resignForced = true;
                log.warn("[DingTalk] 管理员强制放行离职 tenant={} 待离职={} 已见={}", tenantId,
                        candidates.size(), seen);
            }
            case ALLOW -> {
                // 阈值内，正常写入
            }
        }

        for (UserEntity u : candidates) {
            u.setStatus(UserStatus.RESIGNED);
            u.setEnabled(false);
            u.setDisableReason(UserEntity.DISABLE_REASON_RESIGNED);
            userRepository.save(u);
            userStatusService.invalidate(u.getId());
            stats.usersResigned++;
        }
    }

    /**
     * 从钉钉同步单个用户到本地并建立映射（通讯录变更事件 contact_updated 入口）。
     *
     * <p>幂等：{@link UserMappingService#mapUser} 内部走缓存 + 幂等 put，重复事件不会重复建号。
     */
    public UserEntity syncSingleUser(String dingtalkUserId, String name, String tenantId) {
        if (dingtalkUserId == null || dingtalkUserId.isBlank()) {
            throw new IllegalArgumentException("dingtalkUserId 不能为空");
        }
        String username = USERNAME_PREFIX + dingtalkUserId;
        UserEntity user = findExistingUser(tenantId, username, dingtalkUserId).orElse(null);
        if (user == null) {
            user = provisioningService.createExternal(
                    tenantId, username, name, null, SOURCE, dingtalkUserId);
        } else if (name != null && !name.isBlank()) {
            user.setDisplayName(name);
            userRepository.save(user);
        }
        userMappingService.mapUser(dingtalkUserId, user.getId().toString(), tenantId);
        return user;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 获取钉钉访问令牌（仅 {@code dingtalk.single-tenant-legacy=true} 的全局回退路径）。
     *
     * <p>使用可注入的 {@link #restTemplate}，便于测试断言「未配置租户零外部请求」。
     */
    private String getAccessToken() throws Exception {
        String url = "https://oapi.dingtalk.com/gettoken?"
                + "appkey=" + appKey
                + "&appsecret=" + appSecret;
        String response = restTemplate.getForObject(url, String.class);
        JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
        return json.path("access_token").asText("");
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("departmentsSynced", 0);
        m.put("departmentsTotal", 0);
        m.put("usersSynced", 0);
        m.put("usersFailed", 0);
        m.put("usersResigned", 0);
        m.put("dryRun", false);
        m.put("resignSkipped", true);
        m.put("resignSkipReason", "not started");
        m.put("resignBreakerTripped", false);
        m.put("resignForced", false);
        m.put("pendingResignations", List.of());
        m.put("errors", List.of());
        return m;
    }

    /** 同步统计（字段名与飞书 SyncStats 对齐，便于前端复用）。 */
    static final class SyncStats {
        int departmentsSynced;
        int departmentsTotal;
        int usersSynced;
        int usersFailed;
        int usersResigned;
        boolean dryRun;
        boolean force;
        boolean resignSkipped;
        String resignSkipReason;
        boolean resignBreakerTripped;
        boolean resignForced;
        List<String> pendingResignations = List.of();
        final List<String> errors = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("departmentsSynced", departmentsSynced);
            m.put("departmentsTotal", departmentsTotal);
            m.put("usersSynced", usersSynced);
            m.put("usersFailed", usersFailed);
            m.put("usersResigned", usersResigned);
            m.put("dryRun", dryRun);
            m.put("force", force);
            m.put("resignSkipped", resignSkipped);
            m.put("resignSkipReason", resignSkipReason);
            m.put("resignBreakerTripped", resignBreakerTripped);
            m.put("resignForced", resignForced);
            m.put("pendingResignations", List.copyOf(pendingResignations));
            m.put("errors", List.copyOf(errors));
            m.put("timestamp", System.currentTimeMillis());
            return m;
        }
    }
}
