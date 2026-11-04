package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserStatus;
import com.nocobase.auth.UserStatusService;
import com.nocobase.integration.credential.TenantIntegrationCredentialRepository;
import com.nocobase.integration.market.TenantIntegrationInstallRepository;
import com.nocobase.org.OrgUnitEntity;
import com.nocobase.org.OrgUnitService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * A-4 修复验证：多部门树 + 分页 + 更新 + 离职 + 失败上报。
 *
 * <p>修复前该服务只拉 {@code dept_id=1} 一页、已存在用户原样返回、异常吞掉后仍报
 * 「同步 N 人」。本类用可注入的 RestTemplate 模拟两层部门 + 120 人分页，
 * 断言真实写入行为（而非只断言返回值）。
 */
class DingTalkOrgSyncServiceMultiDeptTest {

    private static final String TENANT = "t1";

    private DingTalkOrgSyncService service;
    private UserRepository userRepository;
    private UserProvisioningService provisioning;
    private OrgUnitService orgUnitService;
    private UserStatusService userStatusService;
    private RestTemplate restTemplate;

    /** 内存版用户表：username → 实体。 */
    private final Map<String, UserEntity> users = new LinkedHashMap<>();
    /** dept external id → 本地 org_unit id。 */
    private final Map<String, UUID> deptIds = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        DingTalkAppService appService = mock(DingTalkAppService.class);
        userRepository = mock(UserRepository.class);
        provisioning = mock(UserProvisioningService.class);
        orgUnitService = mock(OrgUnitService.class);
        userStatusService = mock(UserStatusService.class);
        restTemplate = mock(RestTemplate.class);

        service = new DingTalkOrgSyncService(
                appService,
                userRepository,
                mock(UserMappingService.class),
                provisioning,
                orgUnitService,
                userStatusService,
                mock(TenantIntegrationInstallRepository.class),
                mock(TenantIntegrationCredentialRepository.class));
        ReflectionTestUtils.setField(service, "appKey", "k");
        ReflectionTestUtils.setField(service, "appSecret", "s");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        when(appService.getAppAccessToken(TENANT)).thenReturn("token");
        when(appService.isConfigured(TENANT)).thenReturn(true);

        when(orgUnitService.upsertExternal(eq(TENANT), eq("DINGTALK"), any(),
                any(), any(), any(Integer.class)))
                .thenAnswer(inv -> {
                    String externalId = inv.getArgument(2);
                    UUID id = deptIds.computeIfAbsent(externalId,
                            k -> UUID.nameUUIDFromBytes(("dept-" + k).getBytes(StandardCharsets.UTF_8)));
                    OrgUnitEntity u = new OrgUnitEntity();
                    u.setId(id);
                    u.setTenantId(TENANT);
                    u.setExternalId(externalId);
                    u.setName(inv.getArgument(3));
                    u.setSource("DINGTALK");
                    return u;
                });

        when(provisioning.createExternal(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UserEntity u = new UserEntity();
                    u.setId(UUID.randomUUID());
                    u.setUsername(inv.getArgument(1));
                    u.setPasswordHash(null);
                    u.setPasswordSet(false);
                    u.setTenantId(inv.getArgument(0));
                    u.setDisplayName(inv.getArgument(2));
                    return u;
                });

        when(userRepository.findByUsername(any()))
                .thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0))));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        when(userRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenAnswer(inv -> new ArrayList<>(users.values()));

        when(restTemplate.getForObject(any(String.class), eq(String.class)))
                .thenAnswer(inv -> respond(inv.getArgument(0)));
    }

    // ------------------------------------------------------------------
    //  伪钉钉服务：两层部门 + 120 人分页
    // ------------------------------------------------------------------

    /** 每次同步由用例设置：dept 11 的人数（模拟离职导致减少）。 */
    private int dept11UserCount = 10;
    /** 姓名覆盖：external user id → 显示名。 */
    private final Map<String, String> nameOverrides = new LinkedHashMap<>();
    /** 是否让 dept=11 的用户列表接口返回错误。 */
    private boolean failDept11 = false;

    private String respond(String url) {
        if (url.contains("/topapi/v2/dept/list")) {
            if (url.contains("parent_dept_id=1&") || url.endsWith("parent_dept_id=1&page_size=100&cursor=0")) {
                return deptList("[{\"dept_id\":10,\"name\":\"华东大区\",\"parent_dept_id\":1,\"order\":1}]");
            }
            if (url.contains("parent_dept_id=10")) {
                return deptList("[{\"dept_id\":11,\"name\":\"华东-销售\",\"parent_dept_id\":10,\"order\":1}]");
            }
            return deptList("[]");
        }
        if (url.contains("/topapi/v2/user/list")) {
            long deptId = Long.parseLong(extract(url, "dept_id"));
            int cursor = Integer.parseInt(extract(url, "cursor"));
            int size = 100;
            if (deptId == 1) {
                return userPage(List.of(), false, 0);
            }
            if (deptId == 10) {
                // 110 人 → 两页（100 + 10），验证 cursor 分页
                return userPage(usersOf("A", 110, 10, cursor, size), 110 > cursor + size,
                        cursor + size);
            }
            if (deptId == 11) {
                if (failDept11) {
                    return "{\"errcode\":88,\"errmsg\":\"rate limited\"}";
                }
                int total = dept11UserCount;
                return userPage(usersOf("B", total, 11, cursor, size), total > cursor + size,
                        cursor + size);
            }
        }
        return "{}";
    }

    private List<Map<String, Object>> usersOf(String prefix, int total, long dept, int cursor, int size) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = cursor; i < Math.min(total, cursor + size); i++) {
            String userId = prefix + i;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userid", userId);
            m.put("name", nameOverrides.getOrDefault(userId, "员工" + userId));
            m.put("email", userId + "@corp.com");
            m.put("mobile", "1380000" + String.format("%04d", i));
            m.put("title", "工程师");
            m.put("dept_id_list", List.of(dept));
            out.add(m);
        }
        return out;
    }

    private String deptList(String listJson) {
        return "{\"errcode\":0,\"result\":{\"list\":" + listJson + ",\"has_more\":false}}";
    }

    private String userPage(List<Map<String, Object>> list, boolean hasMore, int nextCursor) {
        StringBuilder sb = new StringBuilder("{\"errcode\":0,\"result\":{\"list\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> u = list.get(i);
            sb.append("{\"userid\":\"").append(u.get("userid"))
                    .append("\",\"name\":\"").append(u.get("name"))
                    .append("\",\"email\":\"").append(u.get("email"))
                    .append("\",\"mobile\":\"").append(u.get("mobile"))
                    .append("\",\"title\":\"").append(u.get("title"))
                    .append("\",\"dept_id_list\":[").append(u.get("dept_id_list").toString()
                            .replace("[", "").replace("]", "")).append("]}");
        }
        sb.append("],\"has_more\":").append(hasMore)
                .append(",\"next_cursor\":").append(nextCursor).append("}}");
        return sb.toString();
    }

    private static String extract(String url, String key) {
        int i = url.indexOf(key + "=");
        int start = i + key.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    // ------------------------------------------------------------------
    //  用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两层部门 + 120 人分页 → 全部建号且部门归属正确")
    void syncsAllDepartmentsAndPaginatesUsers() {
        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isEqualTo(0);
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertThat(data.get("departmentsSynced")).isEqualTo(2);
        assertThat(data.get("usersSynced")).isEqualTo(120);
        assertThat(data.get("usersFailed")).isEqualTo(0);

        // 部门归属：A* → dept10 的本地 id；B* → dept11
        UUID unit10 = deptIds.get("10");
        UUID unit11 = deptIds.get("11");
        assertThat(users.get("dt_A0").getDepartmentId()).isEqualTo(unit10);
        assertThat(users.get("dt_B0").getDepartmentId()).isEqualTo(unit11);
        assertThat(users.get("dt_B9").getDepartmentId()).isEqualTo(unit11);
        // A-2：钉钉账号无本地密码
        assertThat(users.get("dt_A0").getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("改名后再次同步 → displayName 被更新（修复前原样返回）")
    void resyncUpdatesExistingUserFields() {
        service.syncOrganization(TENANT);
        assertThat(users.get("dt_A0").getDisplayName()).isEqualTo("员工A0");

        nameOverrides.put("A0", "张三（改名）");
        service.syncOrganization(TENANT);

        assertThat(users.get("dt_A0").getDisplayName()).isEqualTo("张三（改名）");
    }

    @Test
    @DisplayName("人员移出组织结构 → 标记 RESIGNED + enabled=false，不删除")
    void missingUserBecomesResigned() {
        service.syncOrganization(TENANT);
        assertThat(users.get("dt_B9").isEnabled()).isTrue();

        dept11UserCount = 9; // B9 离职
        service.syncOrganization(TENANT);

        UserEntity resigned = users.get("dt_B9");
        assertThat(resigned).isNotNull();
        assertThat(resigned.isEnabled()).isFalse();
        assertThat(resigned.getStatus()).isEqualTo(UserStatus.RESIGNED);
    }

    @Test
    @DisplayName("部门成员接口失败 → usersFailed > 0 且 code != 0（不再假成功）")
    void departmentFailureIsReported() {
        failDept11 = true;

        Map<String, Object> result = service.syncOrganization(TENANT);

        assertThat(result.get("code")).isEqualTo(502);
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertThat((Integer) data.get("usersFailed")).isGreaterThan(0);
        assertThat((List<?>) data.get("errors")).isNotEmpty();
    }
}
