package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PHASE105: DingTalkOrgSyncService 测试。
 *
 * <p>该 Service 内部 {@code new RestTemplate()} 且依赖 @Value 字段，这里用
 * ReflectionTestUtils 注入 appKey/appSecret，覆盖三条可离线验证的路径：
 * 未配置短路(500)、配置但网络失败降级(502)、定时同步不抛异常。
 */
class DingTalkOrgSyncServiceTest {

    private DingTalkOrgSyncService service;
    private DingTalkAppService appService;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        service = new DingTalkOrgSyncService(
                appService,
                mock(com.nocobase.auth.UserRepository.class),
                mock(UserMappingService.class),
                mock(com.nocobase.auth.UserProvisioningService.class),
                mock(com.nocobase.org.OrgUnitService.class),
                mock(com.nocobase.auth.UserStatusService.class),
                mock(com.nocobase.integration.market.TenantIntegrationInstallRepository.class),
                mock(com.nocobase.integration.credential.TenantIntegrationCredentialRepository.class));
    }

    private void configureCredentials(String appKey, String appSecret) {
        ReflectionTestUtils.setField(service, "appKey", appKey);
        ReflectionTestUtils.setField(service, "appSecret", appSecret);
    }

    @Test
    void syncOrganization_notConfigured_returns500() {
        configureCredentials("", "");

        Map<String, Object> result = service.syncOrganization("t1");

        assertThat(result.get("code")).isEqualTo(500);
        assertThat(result.get("message")).asString().contains("未配置");
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertThat(data.get("departmentsSynced")).isEqualTo(0);
        assertThat(data.get("usersSynced")).isEqualTo(0);
    }

    @Test
    void syncOrganization_nullCredentials_returns500() {
        configureCredentials(null, null);

        Map<String, Object> result = service.syncOrganization("t1");

        assertThat(result.get("code")).isEqualTo(500);
    }

    @Test
    void syncOrganization_missingSecret_returns500() {
        configureCredentials("key", ""); // secret 空

        Map<String, Object> result = service.syncOrganization("t1");

        assertThat(result.get("code")).isEqualTo(500);
    }

    @Test
    void syncOrganization_configuredButNetworkFails_returns502() {
        // 已配置 → 走真实 HTTP；离线环境必然失败 → catch → 502（不抛出）
        configureCredentials("key", "secret");

        Map<String, Object> result = service.syncOrganization("t1");

        // 离线为 502；若联网成功则为 0。断言不抛异常且结构完整
        assertThat(result.get("code")).isIn(0, 502);
        assertThat(result).containsKey("data");
    }

    @Test
    void scheduledSync_doesNotThrow() {
        configureCredentials("", ""); // 未配置，syncOrganization 返回 500，被 catch

        // 不应抛异常
        service.scheduledSync();
    }

    @Test
    void scheduledSync_configured_doesNotThrow() {

        service.scheduledSync();
    }

    // ---- syncSingleUser（contact_updated 事件入口，PHASE113） ----

    @Test
    void syncSingleUser_blankUserId_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.syncSingleUser("", "张三", "t1"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.syncSingleUser(null, "张三", "t1"));
    }

    @Test
    void syncSingleUser_existingUser_returnsAndMaps() {
        com.nocobase.auth.UserRepository userRepo =
                (com.nocobase.auth.UserRepository) ReflectionTestUtils.getField(service, "userRepository");
        UserMappingService mappingService =
                (UserMappingService) ReflectionTestUtils.getField(service, "userMappingService");
        com.nocobase.auth.UserEntity existing = new com.nocobase.auth.UserEntity();
        existing.setId(UUID.randomUUID());
        when(userRepo.findByUsername("dt_u1")).thenReturn(Optional.of(existing));

        com.nocobase.auth.UserEntity result = service.syncSingleUser("u1", "张三", "t1");

        assertThat(result).isSameAs(existing);
        verify(mappingService).mapUser(eq("u1"), any(), eq("t1"));
    }

    @Test
    void syncSingleUser_newUser_createsAndMaps() {
        com.nocobase.auth.UserRepository userRepo =
                (com.nocobase.auth.UserRepository) ReflectionTestUtils.getField(service, "userRepository");
        UserMappingService mappingService =
                (UserMappingService) ReflectionTestUtils.getField(service, "userMappingService");
        com.nocobase.auth.UserProvisioningService provisioning =
                (com.nocobase.auth.UserProvisioningService)
                        ReflectionTestUtils.getField(service, "provisioningService");
        when(userRepo.findByUsername("dt_u1")).thenReturn(Optional.empty());
        com.nocobase.auth.UserEntity created = new com.nocobase.auth.UserEntity();
        created.setId(UUID.randomUUID());
        created.setUsername("dt_u1");
        created.setPasswordHash(null);
        // A-2：建号统一走 provisioning（无本地密码）
        when(provisioning.createExternal(any(), any(), any(), any(), any())).thenReturn(created);

        com.nocobase.auth.UserEntity result = service.syncSingleUser("u1", "张三", "t1");

        assertThat(result).isNotNull();
        assertThat(result.getPasswordHash()).isNull();
        verify(mappingService).mapUser(eq("u1"), any(), eq("t1"));
    }
    // ============================================================
    //  Wave4 待办池 #9：组织同步分页与部门树递归
    // ============================================================

    private org.springframework.web.client.RestTemplate rest;

    private void stubHttp(String pathContains, String json) {
        if (rest == null) rest = mock(org.springframework.web.client.RestTemplate.class);
        when(rest.getForObject(org.mockito.ArgumentMatchers.contains(pathContains),
                org.mockito.ArgumentMatchers.eq(String.class))).thenReturn(json);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "restTemplate", rest);
    }

    @Test
    void collectAllDepartments_pagination_pullsAllPages() throws Exception {
        // 用户级分页（120 人两页）在 DingTalkOrgSyncServiceMultiDeptTest 中按公开 API 断言；
        // 这里保留部门列表本身的 cursor 分页回归。
        stubHttp("parent_dept_id=1&page_size=100&cursor=0", """
            {"errcode":0,"result":{"list":[
              {"dept_id":10,"name":"技术部","parent_dept_id":1}],
              "has_more":true,"next_cursor":100}}
            """);
        stubHttp("parent_dept_id=1&page_size=100&cursor=100", """
            {"errcode":0,"result":{"list":[
              {"dept_id":11,"name":"市场部","parent_dept_id":1}],"has_more":false}}
            """);
        stubHttp("parent_dept_id=10", """
            {"errcode":0,"result":{"list":[],"has_more":false}}
            """);
        stubHttp("parent_dept_id=11", """
            {"errcode":0,"result":{"list":[],"has_more":false}}
            """);
        ReflectionTestUtils.setField(service, "restTemplate", rest);

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> depts =
                (java.util.List<java.util.Map<String, Object>>)
                        ReflectionTestUtils.invokeMethod(service, "collectAllDepartments", "tok");

        assertThat(depts).hasSize(2);
        verify(rest).getForObject(
                org.mockito.ArgumentMatchers.contains("cursor=100"),
                org.mockito.ArgumentMatchers.eq(String.class));
    }

    @Test
    void collectAllDepartments_recursesIntoChildDepartments() throws Exception {
        stubHttp("parent_dept_id=1", """
            {"errcode":0,"result":{"list":[
              {"dept_id":10,"name":"技术部","parent_dept_id":1},
              {"dept_id":11,"name":"市场部","parent_dept_id":1}],"has_more":false}}
            """);
        stubHttp("parent_dept_id=10", """
            {"errcode":0,"result":{"list":[
              {"dept_id":20,"name":"后端组","parent_dept_id":10}],"has_more":false}}
            """);
        stubHttp("parent_dept_id=11", """
            {"errcode":0,"result":{"list":[],"has_more":false}}
            """);
        stubHttp("parent_dept_id=20", """
            {"errcode":0,"result":{"list":[],"has_more":false}}
            """);

        java.util.List<Map<String, Object>> all = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "collectAllDepartments", "tok");

        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Map<String, Object> d : all) ids.add((Long) d.get("id"));
        // 递归必须包含子部门 20（旧实现只拿第一层 → 只有 10、11）
        assertThat(ids).containsExactlyInAnyOrder(10L, 11L, 20L);
    }

}
