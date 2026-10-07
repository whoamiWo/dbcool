package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.keystore.KeyRingService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class JwtKeyRotationControllerTest {

    private KeyRingService keyRing;
    private JwtKeyRotationController controller;
    private final AuditService auditService = mock(AuditService.class);

    private static org.springframework.core.env.Environment mockEnv() {
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        return env;
    }

    @BeforeEach
    void setUp() {
        keyRing = new KeyRingService(
                "this-is-a-32-byte-secret-key-for-hmac-sha256!!", "", mockEnv());
        controller = new JwtKeyRotationController(keyRing, auditService);
    }

    @Test
    void snapshot_returnsCurrentKeys() {
        Map<String, Object> resp = controller.snapshot();
        assertThat(resp).containsKey("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("size")).isEqualTo(1);
    }

    @Test
    void rotate_returnsNewKidAndWarning() throws Exception {
        Field f = KeyRingService.class.getDeclaredField("lastRotationAt");
        f.setAccessible(true);
        f.setLong(keyRing, 0);

        Map<String, Object> resp = controller.rotate();
        assertThat(resp).containsKey("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).containsKey("newActiveKid");
        assertThat(data).containsKey("_warning");
        // warning 应提示用户更新 previous-secret
        assertThat((String) data.get("_warning")).contains("previous-secret");
    }

    @Test
    void rotate_tooFrequent_throws429() {
        controller.rotate(); // 第一次成功
        // 立即再次 → 速率超限
        assertThatThrownBy(controller::rotate)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("速率超限");
    }

    /**
     * PHASE85：密钥轮换必须留痕，且 payload **只允许 kid，不得含密钥内容**。
     *
     * <p>这是本批风险最高的一条：轮换埋点如果顺手把 secret 写进 payloadJson，
     * 等于把签名密钥明文落库。
     */
    @Test
    void rotate_logsAuditAndPayloadContainsOnlyKid() throws Exception {
        controller.rotate();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(anyString(), any(), anyString(),
                eq("jwt_key.rotate"), eq("jwt_key"), anyString(), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("newKid");
        assertThat(payload).hasSize(1);

        // 反向：payload 的任何 value 都不允许出现密钥/口令类字段
        for (Object key : payload.keySet()) {
            String k = String.valueOf(key).toLowerCase();
            assertThat(k).doesNotContain("secret").doesNotContain("password")
                    .doesNotContain("token").doesNotContain("key");
        }
    }
}
