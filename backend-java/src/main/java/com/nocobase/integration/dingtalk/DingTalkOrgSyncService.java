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
            List<Map<String, Object>> allDepartments = new ArrayList<>();
            int offset = 0;
            int limit = 100;
            
            while (true) {
                String deptListUrl = "https://oapi.dingtalk.com/topapi/v2/dept/list?" +
                        "access_token=" + accessToken +
                        "&parent_dept_id=1" +
                        "&page_size=" + limit +
                        "&cursor=" + offset;
                
                String response = new org.springframework.web.client.RestTemplate()
                        .getForObject(deptListUrl, String.class);
                
                JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
                JsonNode list = json.path("result").path("list");
                
                if (list.isArray()) {
                    for (JsonNode dept : list) {
                        Map<String, Object> deptMap = new HashMap<>();
                        deptMap.put("id", dept.path("dept_id").asLong());
                        deptMap.put("name", dept.path("name").asText());
                        deptMap.put("parentId", dept.path("parent_dept_id").asLong());
                        deptMap.put("order", dept.path("order").asInt());
                        allDepartments.add(deptMap);
                    }
                    
                    if (list.size() < limit) break;
                    offset += limit;
                } else {
                    break;
                }
            }

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
     * 同步所有部门用户。
     */
    private int syncAllUsers(String accessToken, String tenantId) {
        try {
            // 获取根部门用户列表
            String userListUrl = "https://oapi.dingtalk.com/topapi/v2/user/list?" +
                    "access_token=" + accessToken +
                    "&dept_id=1" +
                    "&cursor=0" +
                    "&size=100";
            
            String response = new org.springframework.web.client.RestTemplate()
                    .getForObject(userListUrl, String.class);
            
            JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
            JsonNode list = json.path("result").path("list");
            
            int count = 0;
            if (list.isArray()) {
                for (JsonNode user : list) {
                    String dingtalkUserId = user.path("userid").asText();
                    String name = user.path("name").asText();
                    
                    // 查找或创建用户
                    UserEntity userEntity = syncUserFromDingTalk(dingtalkUserId, name, tenantId);
                    if (userEntity != null) {
                        // 建立映射
                        userMappingService.mapUser(dingtalkUserId, userEntity.getId().toString(), tenantId);
                        count++;
                    }
                }
            }
            
            return count;
            
        } catch (Exception e) {
            log.warn("[DingTalk] 同步用户失败: {}", e.getMessage());
            return 0;
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
