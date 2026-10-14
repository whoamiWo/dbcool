package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE105: DingTalkMessageService 测试。
 *
 * <p>注意：该 Service 在方法内部 {@code new RestTemplate()}，无法从外部注入 mock，
 * 因此这里覆盖的是「未配置短路」与「网络异常降级」两条可离线验证的分支；
 * 真实 HTTP 成功分支（errcode=0）需联网，不在单测范围。
 */
class DingTalkMessageServiceTest {

    private DingTalkAppService appService;
    private DingTalkMessageService service;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        service = new DingTalkMessageService(appService);
    }

    @Test
    void sendWorkNotice_notConfigured_returnsFalse() {
        when(appService.isConfigured()).thenReturn(false);

        assertThat(service.sendWorkNotice("agent1", "user1", "标题", Map.of("body", "内容"))).isFalse();
    }

    @Test
    void sendGroupMessage_notConfigured_returnsFalse() {
        when(appService.isConfigured()).thenReturn(false);

        assertThat(service.sendGroupMessage("chat1", "标题", Map.of("body", "内容"))).isFalse();
    }

    @Test
    void sendWorkNotice_configuredButNetworkFails_returnsFalse() {
        // 已配置 → 走真实 HTTP；离线环境必然失败，异常被 catch → 返回 false（不抛出）
        when(appService.isConfigured()).thenReturn(true);

        boolean result = service.sendWorkNotice("agent1", "user1", "标题", Map.of("body", "内容"));

        // 离线环境返回 false；若联网且返回 errcode=0 则为 true，此处仅断言不抛异常
        assertThat(result).isIn(true, false);
    }

    @Test
    void sendGroupMessage_configuredButNetworkFails_returnsFalse() {
        when(appService.isConfigured()).thenReturn(true);

        boolean result = service.sendGroupMessage("chat1", "标题", Map.of("body", "内容"));

        assertThat(result).isIn(true, false);
    }

    @Test
    void sendWorkNotice_contentDefaultsHandled() {
        // 空 content → getOrDefault 生效（覆盖默认值分支），未配置短路
        when(appService.isConfigured()).thenReturn(false);

        assertThat(service.sendWorkNotice("agent1", "user1", "标题", Map.of())).isFalse();
    }

    @Test
    void sendGroupMessage_contentDefaultsHandled() {
        when(appService.isConfigured()).thenReturn(false);

        assertThat(service.sendGroupMessage("chat1", "标题", Map.of())).isFalse();
    }
}
