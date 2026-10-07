package com.nocobase.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.integration.dingtalk.DingTalkAppService;
import com.nocobase.integration.feishu.FeishuAppService;
import com.nocobase.integration.mattermost.MattermostAppService;
import com.nocobase.audit.AuditService;
import com.nocobase.ratelimit.GlobalRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.mockito.Mockito;

/**
 * 默认安全回归测试 (T3).
 *
 * <p>每个高危判定点都写测试：**模拟"配置缺失 / 依赖不可用"，
 * 断言结果是拒绝而非放行**。
 */
class DefaultSecurityRegressionTest {

    private final AuditService auditService = new AuditService(null, new ObjectMapper());

    // ==================== Feishu ====================

    @Test
    void feishu_verifySignature_returnsFalse_whenEncryptKeyMissing() throws Exception {
        FeishuAppService mockService = Mockito.spy(new FeishuAppService());
        Field f = FeishuAppService.class.getDeclaredField("encryptKey");
        f.setAccessible(true);
        f.set(mockService, null);

        boolean result = mockService.verifySignature("1234567890", "nonce123", "invalid-signature", "{\"type\":\"event\"}");

        assertThat(result).isFalse();
    }

    @Test
    void feishu_verifySignature_returnsFalse_whenEncryptKeyBlank() throws Exception {
        FeishuAppService mockService = Mockito.spy(new FeishuAppService());
        Field f = FeishuAppService.class.getDeclaredField("encryptKey");
        f.setAccessible(true);
        f.set(mockService, "");

        boolean result = mockService.verifySignature("1234567890", "nonce123", "invalid-signature", "{\"type\":\"event\"}");

        assertThat(result).isFalse();
    }

    // ==================== Mattermost ====================

    @Test
    void mattermost_verifyWebhookToken_returnsFalse_whenTokenMissing_andRequireTokenTrue() throws Exception {
        MattermostAppService service = new MattermostAppService(null);
        Field f = MattermostAppService.class.getDeclaredField("requireToken");
        f.setAccessible(true);
        f.set(service, true);

        boolean result = service.verifyWebhookToken("any-token");

        assertThat(result).isFalse();
    }

    // ==================== Slack ====================

    @Test
    void slack_verifySignature_returnsFalse_whenSigningSecretMissing() throws Exception {
        com.nocobase.integration.slack.SlackAppService service = Mockito.mock(com.nocobase.integration.slack.SlackAppService.class);
        Mockito.when(service.getSigningSecret()).thenReturn(null);
        Mockito.when(service.isConfigured()).thenReturn(false);

        com.nocobase.integration.slack.SlackController controller = 
                new com.nocobase.integration.slack.SlackController(service, new RestTemplate());

        org.springframework.http.ResponseEntity<String> resp = controller.eventsCallback(
                "{\"type\":\"url_verification\",\"challenge\":\"test\"}",
                "1234567890",
                "v0=invalid"
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== DingTalk ====================

    @Test
    void dingtalk_verifySignature_returnsFalse_whenAppSecretMissing() throws Exception {
        DingTalkAppService appService = Mockito.mock(DingTalkAppService.class);
        Mockito.when(appService.getAppSecret()).thenReturn(null);

        com.nocobase.integration.dingtalk.DingTalkController controller = 
                new com.nocobase.integration.dingtalk.DingTalkController(
                        Mockito.mock(com.nocobase.integration.dingtalk.DingTalkAdapter.class),
                        appService,
                        Mockito.mock(com.nocobase.integration.dingtalk.DingTalkApprovalService.class),
                        Mockito.mock(com.nocobase.integration.dingtalk.DingTalkOrgSyncService.class),
                        Mockito.mock(com.nocobase.integration.dingtalk.UserMappingService.class),
                        Mockito.mock(com.nocobase.workflow.WorkflowInstanceRepository.class),
                        Mockito.mock(com.nocobase.workflow.WorkflowTaskRepository.class),
                        Mockito.mock(com.nocobase.auth.JwtService.class),
                        Mockito.mock(com.nocobase.auth.RefreshTokenService.class),
                        auditService
                );

        // 调用 approval-callback 缺少签名头 -> 401
        org.springframework.http.ResponseEntity<Map<String, Object>> resp = controller.approvalCallback(Map.of(), null, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== Redis 限流降级 ====================

    @Test
    void redisRateLimiter_fallsBackToMemory_whenRedisUnavailable() {
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        Mockito.when(mockRedis.getConnectionFactory()).thenReturn(null);

        GlobalRateLimiter limiter = new GlobalRateLimiter(mockRedis);

        boolean r1 = limiter.allowRequest("test-key", 3, 60);
        boolean r2 = limiter.allowRequest("test-key", 3, 60);
        boolean r3 = limiter.allowRequest("test-key", 3, 60);
        boolean r4 = limiter.allowRequest("test-key", 3, 60);

        assertThat(r1).isTrue();
        assertThat(r2).isTrue();
        assertThat(r3).isTrue();
        assertThat(r4).isFalse();
    }
}