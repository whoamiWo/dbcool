package com.nocobase.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserStatus;
import com.nocobase.auth.UserStatusService;
import com.nocobase.integration.credential.TenantCredentialService;
import com.nocobase.org.OrgUnitEntity;
import com.nocobase.org.OrgUnitService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * D3：飞书组织架构同步 —— 两层部门、分页、改名、离职、失败上报、开关默认关闭。
 */
class FeishuOrgSyncServiceMultiDeptTest {

    private static final String TENANT = "t1";

    private FeishuAppService appService;
    private FeishuOrgSyncService service;
    private UserRepository userRepository;
    private OrgUnitService orgUnitService;
    private UserStatusService userStatusService;
    private RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, UserEntity> users = new LinkedHashMap<>();
    private final Map<String, UUID> deptIds = new LinkedHashMap<>();
    private final Map<String, String> deptParents = new LinkedHashMap<>();

    private boolean failDepartmentUsers = false;

    @BeforeEach
    void setUp() throws Exception {
        TenantCredentialService credSvc = mock(TenantCredentialService.class);
        when(credSvc.get(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(credSvc.get(eq(TENANT), eq("feishu"), eq("app-id")))
                .thenReturn(Optional.of("app-id"));
        when(credSvc.get(eq(TENANT), eq("feishu"), eq("app-secret")))
                .thenReturn(Optional.of("app-secret"));

        appService = new FeishuAppService(credSvc);
        userRepository = mock(UserRepository.class);
        UserProvisioningService provisioning = mock(UserProvisioningService.class);
        orgUnitService = mock(OrgUnitService.class);
        userStatusService = mock(UserStatusService.class);

        service = new FeishuOrgSyncService(appService, userRepository, provisioning,
                orgUnitService, userStatusService);
        ReflectionTestUtils.setField(service, "syncEnabled", true);

        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(appService, "restTemplate", restTemplate);
        stubRest();

        when(orgUnitService.upsertExternal(eq(TENANT), eq("FEISHU"), anyString(),
                anyString(), any(), anyInt()))
                .thenAnswer(inv -> {
                    String externalId = inv.getArgument(2);
                    String parent = inv.getArgument(4);
                    UUID id = deptIds.computeIfAbsent(externalId,
                            k -> UUID.nameUUIDFromBytes(("dept-" + k).getBytes(StandardCharsets.UTF_8)));
                    deptParents.put(externalId, parent);
                    OrgUnitEntity u = new OrgUnitEntity();
                    u.setId(id);
                    u.setTenantId(TENANT);
                    u.setExternalId(externalId);
                    u.setName(inv.getArgument(3));
                    u.setSource("FEISHU");
                    return u;
                });

        when(provisioning.createExternal(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    UserEntity u = new UserEntity();
                    u.setId(UUID.randomUUID());
                    u.setUsername(inv.getArgument(1));
                    u.setPasswordHash(null);
                    u.setPasswordSet(false);
                    u.setTenantId(inv.getArgument(0));
                    u.setDisplayName(inv.getArgument(2));
                    u.setExternalSource(inv.getArgument(4));
                    u.setExternalId(inv.getArgument(5));
                    return u;
                });

        when(userRepository.findByTenantIdAndExternalSourceAndExternalId(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String tenant = inv.getArgument(0);
                    String source = inv.getArgument(1);
                    String externalId = inv.getArgument(2);
                    return users.values().stream()
                            .filter(u -> tenant.equals(u.getTenantId()))
                            .filter(u -> source.equals(u.getExternalSource()))
                            .filter(u -> externalId.equals(u.getExternalId()))
                            .findFirst();
                });
        when(userRepository.findByTenantIdAndUsername(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String tenant = inv.getArgument(0);
                    UserEntity u = users.get(inv.getArgument(1));
                    return Optional.ofNullable(u).filter(x -> tenant.equals(x.getTenantId()));
                });
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        when(userRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenAnswer(inv -> new ArrayList<>(users.values()));
    }

    private void stubRest() {
        JsonNode token = mapper.createObjectNode()
                .put("code", 0)
                .put("tenant_access_token", "tok")
                .put("expire", 7200);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(token));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(JsonNode.class)))
                .thenAnswer(inv -> {
                    String url = inv.getArgument(0);
                    if (failDepartmentUsers && url.contains("/contact/v3/users")) {
                        throw new RuntimeException("429 Too Many Requests");
                    }
                    return ResponseEntity.ok(feishuResponse(url));
                });
    }

    /** 伪飞书通讯录：0 → d1 → d11；d1 110 人分页，d11 10 人。 */
    private JsonNode feishuResponse(String url) {
        ObjectNode root = mapper.createObjectNode().put("code", 0).put("msg", "ok");
        ObjectNode data = root.putObject("data");

        if (url.contains("/contact/v3/departments/0/children")) {
            ArrayNode items = data.putArray("items");
            ObjectNode d1 = items.addObject();
            d1.put("open_department_id", "d1");
            d1.put("parent_department_id", "0");
            d1.put("name", "研发中心");
            d1.put("order", 1);
        } else if (url.contains("/contact/v3/departments/d1/children")) {
            ArrayNode items = data.putArray("items");
            ObjectNode d11 = items.addObject();
            d11.put("open_department_id", "d11");
            d11.put("parent_department_id", "d1");
            d11.put("name", "平台组");
            d11.put("order", 1);
        } else if (url.contains("/contact/v3/departments/d11/children")) {
            data.putArray("items");
        } else if (url.contains("/contact/v3/users")) {
            usersPage(url, data);
        }
        // 仅在本分支未显式设置分页标志时给默认值（usersPage 会设置 has_more/page_token）
        if (!data.has("has_more")) data.put("has_more", false);
        return root;
    }

    private void usersPage(String url, ObjectNode data) {
        ArrayNode items = data.putArray("items");
        String dept = extract(url, "department_id");
        String pageToken = extract(url, "page_token");
        if ("d1".equals(dept)) {
            if (pageToken.isBlank()) {
                for (int i = 0; i < 50; i++) addUser(items, "u" + i, "研发" + i);
                data.put("has_more", true);
                data.put("page_token", "p2");
            } else if ("p2".equals(pageToken)) {
                for (int i = 50; i < 100; i++) addUser(items, "u" + i, "研发" + i);
                data.put("has_more", true);
                data.put("page_token", "p3");
            } else {
                for (int i = 100; i < 110; i++) addUser(items, "u" + i, "研发" + i);
                data.put("has_more", false);
            }
        } else if ("d11".equals(dept)) {
            for (int i = 0; i < 10; i++) addUser(items, "w" + i, "平台" + i);
            data.put("has_more", false);
        }
    }

    private void addUser(ArrayNode items, String openId, String name) {
        ObjectNode u = items.addObject();
        u.put("open_id", openId);
        u.put("name", name);
        u.put("email", openId + "@example.com");
        u.put("mobile", "1380000" + openId.hashCode() % 1000);
        u.put("job_title", "工程师");
        u.put("leader_user_id", "");
        ArrayNode depts = u.putArray("department_ids");
        depts.add(openId.startsWith("w") ? "d11" : "d1");
    }

    private static String extract(String url, String key) {
        int idx = url.indexOf(key + "=");
        if (idx < 0) return "";
        int start = idx + key.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    @Test
    @DisplayName("两层部门 + 110/10 人分页 → 部门写入 org_unit，用户全量同步")
    void twoLevelDepartmentsAndPagedUsers() {
        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("departmentsSynced")).isEqualTo(2);
        assertThat(data.get("usersSynced")).isEqualTo(120);
        assertThat(data.get("usersFailed")).isEqualTo(0);

        assertThat(deptParents.get("d1")).isNull();
        assertThat(deptParents.get("d11")).isEqualTo("d1");
        assertThat(users).hasSize(120);
        assertThat(users).containsKey("fs_u0");
        assertThat(users).containsKey("fs_w9");
        // 分页确实被跟随（不是只取第一页）
        verify(restTemplate).exchange(org.mockito.ArgumentMatchers.contains("page_token=p2"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
        verify(restTemplate).exchange(org.mockito.ArgumentMatchers.contains("page_token=p3"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("已存在用户改名 → 逐字段更新（不再原样返回）")
    void existingUserRenamed() {
        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername("fs_u0");
        existing.setTenantId(TENANT);
        existing.setDisplayName("旧名字");
        existing.setStatus(UserStatus.ACTIVE);
        existing.setEnabled(true);
        users.put("fs_u0", existing);

        service.syncOrganization(TENANT);

        assertThat(users.get("fs_u0").getDisplayName()).isEqualTo("研发0");
    }

    @Test
    @DisplayName("本轮未出现的飞书用户 → RESIGNED + enabled=false + 会话失效")
    void absentFeishuUserResigned() {
        UserEntity ghost = new UserEntity();
        ghost.setId(UUID.randomUUID());
        ghost.setUsername("fs_ghost");
        ghost.setTenantId(TENANT);
        ghost.setDisplayName("已离职");
        ghost.setStatus(UserStatus.ACTIVE);
        ghost.setEnabled(true);
        users.put("fs_ghost", ghost);

        service.syncOrganization(TENANT);

        assertThat(ghost.getStatus()).isEqualTo(UserStatus.RESIGNED);
        assertThat(ghost.isEnabled()).isFalse();
        verify(userStatusService).invalidate(ghost.getId());
    }

    @Test
    @DisplayName("部门成员接口 429 → usersFailed > 0 且 code != 0")
    void departmentUsersRateLimited_reportsFailure() {
        failDepartmentUsers = true;

        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isNotEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat((Integer) data.get("usersFailed")).isGreaterThan(0);
        assertThat((List<?>) data.get("errors")).isNotEmpty();
    }

    @Test
    @DisplayName("F1：单部门 429 → 不执行离职，原用户状态与会话不变")
    void departmentUsersRateLimited_doesNotResignAnyone() {
        UserEntity ghost = new UserEntity();
        ghost.setId(UUID.randomUUID());
        ghost.setUsername("fs_ghost");
        ghost.setTenantId(TENANT);
        ghost.setStatus(UserStatus.ACTIVE);
        ghost.setEnabled(true);
        users.put("fs_ghost", ghost);
        failDepartmentUsers = true;

        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isNotEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("usersResigned")).isEqualTo(0);
        assertThat(data.get("resignSkipped")).isEqualTo(true);
        assertThat(ghost.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(ghost.isEnabled()).isTrue();
        verify(userStatusService, never()).invalidate(any());
    }

    @Test
    @DisplayName("F2：带租户后缀的飞书账号按 external_id 判在离职，不被误判")
    void suffixedFeishuUsername_matchedByExternalId() {
        UserEntity suffixed = new UserEntity();
        suffixed.setId(UUID.randomUUID());
        suffixed.setUsername("fs_u0_tenantb"); // 跨租户冲突后的稳定后缀
        suffixed.setTenantId(TENANT);
        suffixed.setExternalSource("FEISHU");
        suffixed.setExternalId("u0"); // 本轮飞书返回的就是 u0
        suffixed.setStatus(UserStatus.ACTIVE);
        suffixed.setEnabled(true);
        users.put(suffixed.getUsername(), suffixed);

        service.syncOrganization(TENANT);

        assertThat(suffixed.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(suffixed.isEnabled()).isTrue();
        assertThat(suffixed.getDisplayName()).isEqualTo("研发0");
        assertThat(users).doesNotContainKey("fs_u0"); // 未重复建号
    }

    @Test
    @DisplayName("F1：dryRun → 返回待离职名单但不写库、不吊销会话")
    void dryRun_returnsPendingWithoutWriting() {
        UserEntity ghost = new UserEntity();
        ghost.setId(UUID.randomUUID());
        ghost.setUsername("fs_ghost");
        ghost.setTenantId(TENANT);
        ghost.setStatus(UserStatus.ACTIVE);
        ghost.setEnabled(true);
        users.put("fs_ghost", ghost);

        Map<String, Object> result = service.syncOrganization(TENANT, true);

        assertThat(result.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("usersResigned")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> pending = (List<String>) data.get("pendingResignations");
        assertThat(pending).contains("fs_ghost");
        assertThat(ghost.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(ghost.isEnabled()).isTrue();
        verify(userStatusService, never()).invalidate(any());
    }

    @Test
    @DisplayName("F1：离职人数超过阈值 → 熔断 code=409 且零写入")
    void resignRatioBreaker_rejectsBulkResign() {
        for (int i = 0; i < 11; i++) {
            UserEntity ghost = new UserEntity();
            ghost.setId(UUID.randomUUID());
            ghost.setUsername("fs_ghost" + i);
            ghost.setTenantId(TENANT);
            ghost.setStatus(UserStatus.ACTIVE);
            ghost.setEnabled(true);
            users.put("fs_ghost" + i, ghost);
        }

        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("usersResigned")).isEqualTo(0);
        assertThat(data.get("resignBreakerTripped")).isEqualTo(true);
        assertThat((List<?>) data.get("pendingResignations")).hasSize(11);
        assertThat(users.get("fs_ghost0").getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userStatusService, never()).invalidate(any());
    }

    @Test
    @DisplayName("G3：force=true → 跳过熔断并执行离职（计划内大裁员）")
    void force_releasesBreakerAndResigns() {
        for (int i = 0; i < 11; i++) {
            UserEntity ghost = new UserEntity();
            ghost.setId(UUID.randomUUID());
            ghost.setUsername("fs_ghost" + i);
            ghost.setTenantId(TENANT);
            ghost.setStatus(UserStatus.ACTIVE);
            ghost.setEnabled(true);
            users.put("fs_ghost" + i, ghost);
        }

        Map<String, Object> result = service.syncOrganization(TENANT, false, true);

        assertThat(result.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("usersResigned")).isEqualTo(11);
        assertThat(data.get("resignBreakerTripped")).isEqualTo(false);
        assertThat(data.get("resignForced")).isEqualTo(true);
        assertThat(users.get("fs_ghost0").getStatus()).isEqualTo(UserStatus.RESIGNED);
        assertThat(users.get("fs_ghost10").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("F4：管理员手工禁用 → 同步不自动启用、不判离职")
    void manuallyDisabledUser_isNotReenabledOrResigned() {
        UserEntity disabled = new UserEntity();
        disabled.setId(UUID.randomUUID());
        disabled.setUsername("fs_u0"); // 本轮仍出现在飞书
        disabled.setTenantId(TENANT);
        disabled.setStatus(UserStatus.DISABLED);
        disabled.setEnabled(false);
        disabled.setDisableReason(UserEntity.DISABLE_REASON_MANUAL);
        users.put("fs_u0", disabled);

        service.syncOrganization(TENANT);

        assertThat(disabled.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getDisableReason()).isEqualTo(UserEntity.DISABLE_REASON_MANUAL);
    }

    @Test
    @DisplayName("F2：同名用户属于其他租户 → 本租户同步不改写对方")
    void otherTenantSameUsername_isNotTouched() {
        UserEntity tenantA = new UserEntity();
        tenantA.setId(UUID.randomUUID());
        tenantA.setUsername("fs_u0");
        tenantA.setTenantId("tenantA");
        tenantA.setDisplayName("A 公司员工");
        tenantA.setStatus(UserStatus.ACTIVE);
        tenantA.setEnabled(true);
        when(userRepository.findByUsername("fs_u0")).thenReturn(Optional.of(tenantA));
        // 注意：不在 users（本租户）里放任何 fs_u0

        service.syncOrganization(TENANT);

        assertThat(tenantA.getTenantId()).isEqualTo("tenantA");
        assertThat(tenantA.getDisplayName()).isEqualTo("A 公司员工");
        assertThat(users.get("fs_u0").getTenantId()).isEqualTo(TENANT);
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("feishu.sync.enabled=false → 拒绝且零外部请求")
    void disabled_makesNoExternalRequests() {
        ReflectionTestUtils.setField(service, "syncEnabled", false);

        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isEqualTo(500);
        assertThat(String.valueOf(result.get("message"))).contains("未启用");
        verifyNoInteractions(restTemplate);
        verify(orgUnitService, never()).upsertExternal(anyString(), anyString(), anyString(),
                anyString(), any(), anyInt());
    }
}
