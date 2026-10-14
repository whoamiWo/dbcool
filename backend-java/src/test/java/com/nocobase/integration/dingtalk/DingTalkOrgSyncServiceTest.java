package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
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

    @BeforeEach
    void setUp() {
        service = new DingTalkOrgSyncService(
                mock(DingTalkAppService.class),
                mock(com.nocobase.auth.UserRepository.class),
                mock(UserMappingService.class));
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
        configureCredentials("key", "secret");

        service.scheduledSync();
    }
}
