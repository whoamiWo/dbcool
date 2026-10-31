package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 钉钉组织架构同步服务 — 部门/用户列表拉取与定时同步。
 *
 * <p><b>Week 2 任务</b>: 实现钉钉组织架构到本地数据库的完整同步链路。
 * <ul>
 *   <li>手动触发：通过 {@link DingTalkController#syncOrg(Map)} 立即执行</li>
 *   <li>定时任务：每小时自动同步一次 (@Scheduled(cron = "0 0 * * * ?"))</li>
 * </ul>
 *
 * <p>同步逻辑:
 * <ol>
 *   <li>分页调用 /topapi/v2/dept/list 获取所有部门</li>
 *   <li>递归构建部门树结构</li>
 *   <li>对每个部门调用 /topapi/v2/user/list 获取成员</li>
 *   <li>通过 {@link UserMappingService} 建立钉钉 userId ↔ 平台 userId 映射</li>
 * </ol>
 */
@Service
public class DingTalkOrgSyncService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkOrgSyncService.class);

    private final DingTalkAppService appService;
    private final UserRepository userRepository;
    private final UserMappingService userMappingService;
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

    public DingTalkOrgSyncService(DingTalkAppService appService,
                                   UserRepository userRepository,
                                   UserMappingService userMappingService) {
        this.appService = appService;
        this.userRepository = userRepository;
        this.userMappingService = userMappingService;
    }

    /** 是否已配置钉钉应用凭证。 */
    private boolean isConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * 手动触发组织架构同步。
     *
     * @param tenantId 租户 ID
     * @return 同步结果统计
     */
    @Transactional
    public Map<String, Object> syncOrganization(String tenantId) {
        if (!isConfigured()) {
            return Map.of("code", 500,
                    "message", "钉钉应用未配置 (dingtalk.app-key / app-secret)",
                    "data", Map.of("departmentsSynced", 0, "usersSynced", 0));
        }

        try {
            log.info("[DingTalk] 开始同步组织架构，tenantId={}", tenantId);

            // 1. 获取访问令牌
            String accessToken = getAccessToken();
            
            // 2. 获取所有部门列表
            List<Map<String, Object>> allDepartments = collectAllDepartments(accessToken);

            // 3. 同步用户（通过钉钉通讯录获取用户并映射到本地）
            int usersSynced = syncAllUsers(accessToken, tenantId);

            log.info("[DingTalk] 组织架构同步完成，用户={}个", usersSynced);

            return Map.of("code", 0,
                    "message", "success",
                    "data", Map.of(
                            "departmentsSynced", allDepartments.size(),
                            "usersSynced", usersSynced,
                            "timestamp", System.currentTimeMillis()
                    ));

        } catch (Exception e) {
            log.error("[DingTalk] 组织架构同步失败: {}", e.getMessage(), e);
            return Map.of("code", 502,
                    "message", "同步失败：" + e.getMessage(),
                    "data", Map.of("departmentsSynced", 0, "usersSynced", 0));
        }
    }

    /**
     * 定时同步任务 - 每小时执行一次。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledSync() {
        log.info("[DingTalk] 开始定时组织架构同步");
        try {
            syncOrganization("tenant_default");
        } catch (Exception e) {
            log.error("[DingTalk] 定时同步失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 收集全部部门（Wave4 待办池 #9）。
     *
     * <p>此前只调用一次 {@code /topapi/v2/dept/list?parent_dept_id=1} ——
     * 钉钉该接口返回的是**指定部门的直接子部门**，因此只拿到第一层部门，
     * 子部门全部丢失。现在按部门树 BFS 递归拉取，并对每层做 cursor 分页。
     *
     * @param accessToken 钉钉 access_token
     * @return 全量部门列表（含根部门下的所有层级）
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
                String url = "https://oapi.dingtalk.com/topapi/v2/dept/list?" +
                        "access_token=" + accessToken +
                        "&parent_dept_id=" + parentId +
                        "&page_size=" + size +
                        "&cursor=" + cursor;
                String response = restTemplate.getForObject(url, String.class);
                JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
                // Wave4 #9：errcode != 0 显式失败（此前静默返回空部门 → 假成功）
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

    /**
     * 同步所有部门用户。
     */
    private int syncAllUsers(String accessToken, String tenantId) {
        int count = 0;
        try {
            // Wave4 待办池 #9：此前只拉 cursor=0&size=100 一页 —— 超过 100 人的企业
            // 会静默漏人（同步仍报 success）。现按 cursor 循环拉全量。
            int cursor = 0;
            int size = 100;
            int pages = 0;
            int maxPages = 500; // 防御性上限
            while (pages < maxPages) {
                String userListUrl = "https://oapi.dingtalk.com/topapi/v2/user/list?" +
                        "access_token=" + accessToken +
                        "&dept_id=1" +
                        "&cursor=" + cursor +
                        "&size=" + size;

                String response = restTemplate.getForObject(userListUrl, String.class);
                JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
                // Wave4 #9：errcode != 0 显式失败（此前静默返回空列表 → 假成功）
                int errcode = json.path("errcode").asInt(0);
                if (errcode != 0) {
                    throw new IllegalStateException("钉钉用户列表获取失败：errcode=" + errcode
                            + " errmsg=" + json.path("errmsg").asText("unknown"));
                }
                JsonNode result = json.path("result");
                JsonNode list = result.path("list");
                pages++;

                if (list.isArray()) {
                    for (JsonNode user : list) {
                        String dingtalkUserId = user.path("userid").asText();
                        String name = user.path("name").asText();
                        if (dingtalkUserId.isBlank()) {
                            continue;
                        }
                        UserEntity userEntity = syncUserFromDingTalk(dingtalkUserId, name, tenantId);
                        if (userEntity != null) {
                            userMappingService.mapUser(dingtalkUserId, userEntity.getId().toString(), tenantId);
                            count++;
                        }
                    }
                }

                boolean hasMore = result.path("has_more").asBoolean(false);
                long nextCursor = result.path("next_cursor").asLong(cursor + size);
                if (!hasMore || list.isEmpty() || nextCursor <= cursor) {
                    break;
                }
                cursor = (int) nextCursor;
            }
            log.info("[DingTalk] 用户同步完成：{} 个（{} 页）", count, pages);
            return count;

        } catch (Exception e) {
            log.warn("[DingTalk] 同步用户失败: {}", e.getMessage());
            return count;
        }
    }

    /**
     * 从钉钉同步单个用户到本地。
     */
    private UserEntity syncUserFromDingTalk(String dingtalkUserId, String name, String tenantId) {
        // 查找已有用户
        Optional<UserEntity> existing = userRepository.findByUsername("dt_" + dingtalkUserId);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // 创建新用户
        UserEntity user = new UserEntity();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("dt_" + dingtalkUserId);
        user.setDisplayName(name);
        user.setTenantId(tenantId);
        user.setEnabled(true);
        user.setCreatedAt(java.time.Instant.now());
        
        userRepository.save(user);
        log.info("[DingTalk] 新建用户: {} (tenant={})", dingtalkUserId, tenantId);
        
        return user;
    }

    /**
     * 同步单个钉钉用户到本地并建立映射（通讯录变更事件 contact_updated 入口）。
     *
     * <p>PHASE113：此前 {@code contact_updated} 事件未实现，仅打日志 + 返回 processed=false，
     * 钉钉会重投但本地永远无此用户。现提供此公开方法，把单个用户的查找/创建 + 映射
     * 封装为可复用能力，事件回调直接调用即可真实同步。
     *
     * <p>幂等：{@link UserMappingService#mapUser} 内部走缓存 + 幂等 put，重复事件不会重复建号。
     *
     * @return 同步成功的平台用户实体
     * @throws IllegalArgumentException 入参非法（如 dingtalkUserId 为空）
     */
    public UserEntity syncSingleUser(String dingtalkUserId, String name, String tenantId) {
        if (dingtalkUserId == null || dingtalkUserId.isBlank()) {
            throw new IllegalArgumentException("dingtalkUserId 不能为空");
        }
        UserEntity user = syncUserFromDingTalk(dingtalkUserId, name, tenantId);
        userMappingService.mapUser(dingtalkUserId, user.getId().toString(), tenantId);
        return user;
    }

    /**
     * 获取钉钉访问令牌。
     */
    private String getAccessToken() throws Exception {
        String url = "https://oapi.dingtalk.com/gettoken?" +
                "appkey=" + appKey +
                "&appsecret=" + appSecret;
        
        String response = new org.springframework.web.client.RestTemplate()
                .getForObject(url, String.class);
        
        JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
        return json.path("access_token").asText("");
    }
}
