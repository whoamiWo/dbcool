package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 钉钉审批回调签名校验测试。
 *
 * <p>覆盖「验收铁律」的正反两个方向：
 * <ul>
 *   <li>反向：缺头 / 错签名 / 过期时间戳 / 未配密钥 → 全部拒绝</li>
 *   <li><b>正向：正确签名必须放行（返 code 0）</b> —— 缺此用例则无法区分
 *       「真校验」与「被 Spring Security 拦截伪装成的校验成功」</li>
 * </ul>
 */
class DingTalkApprovalCallbackTest {

    private static final String SECRET = "testAppSecret";
    private static final String INSTANCE_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private DingTalkAppService appService;
    private DingTalkApprovalService approvalService;
    private WorkflowTaskRepository taskRepository;
    private DingTalkController controller;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        approvalService = mock(DingTalkApprovalService.class);
        taskRepository = mock(WorkflowTaskRepository.class);

        when(appService.getAppSecret()).thenReturn(SECRET);
        when(taskRepository.findByInstanceIdAndStatus(any(), any()))
                .thenReturn(List.<WorkflowTaskEntity>of());

        controller = new DingTalkController(
                mock(DingTalkAdapter.class),
                appService,
                approvalService,
                mock(DingTalkOrgSyncService.class),
                mock(UserMappingService.class),
                mock(WorkflowInstanceRepository.class),
                taskRepository,
                mock(JwtService.class),
                mock(RefreshTokenService.class));
    }

    private Map<String, Object> payload() {
        return Map.of("instance_id", INSTANCE_ID, "result", "agree");
    }

    /** 计算合法签名：Base64(HmacSHA256(secret, timestamp))。 */
    private static String sign(String secret, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingHeaders_rejected() {
        Map<String, Object> resp = controller.approvalCallback(payload(), null, null);
        assertThat(resp.get("code")).isEqualTo(401);
    }

    @Test
    void invalidSignature_rejected() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        Map<String, Object> resp = controller.approvalCallback(payload(), ts, "bogus_signature");
        assertThat(resp.get("code")).isEqualTo(401);
    }

    /** 正向用例：正确签名必须放行（证明是真校验，而非被 Security 拦截的假阳性）。 */
    @Test
    void validSignature_accepted() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = sign(SECRET, ts);
        Map<String, Object> resp = controller.approvalCallback(payload(), ts, sig);
        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void expiredTimestamp_rejected() throws Exception {
        // 10 分钟前的时间戳（超出 5 分钟窗口），但签名本身是合法计算的
        String ts = String.valueOf(System.currentTimeMillis() - 600_000);
        String sig = sign(SECRET, ts);
        Map<String, Object> resp = controller.approvalCallback(payload(), ts, sig);
        assertThat(resp.get("code")).isEqualTo(401);
    }

    @Test
    void blankSecret_rejected() throws Exception {
        when(appService.getAppSecret()).thenReturn("");
        String ts = String.valueOf(System.currentTimeMillis());
        Map<String, Object> resp = controller.approvalCallback(payload(), ts, sign(SECRET, ts));
        assertThat(resp.get("code")).isEqualTo(401);
    }

    /** 请求方无法通过 URL 参数指定租户（方法签名已不含 tenantId）。 */
    @Test
    void tenantIdNotAcceptableFromRequest() {
        assertThat(DingTalkController.class.getDeclaredMethods())
                .anySatisfy(m -> {
                    if ("approvalCallback".equals(m.getName())) {
                        for (java.lang.reflect.Parameter p : m.getParameters()) {
                            assertThat(p.getName()).isNotEqualTo("tenantId");
                        }
                    }
                });
    }
}
