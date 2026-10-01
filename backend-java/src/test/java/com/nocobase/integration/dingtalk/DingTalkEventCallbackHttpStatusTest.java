package com.nocobase.integration.dingtalk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 钉钉**事件回调**（{@code POST /api/dingtalk/events}）的 HTTP 状态码测试。
 *
 * <p>背景：该端点此前验签失败时返回「HTTP 200 + 业务码 401」，
 * 会让第三方/网关误判为成功，也不会触发钉钉的失败重投。
 * 现已改为真正的 HTTP 401 —— 本类即为该行为的回归防线。
 *
 * <p>注意与 {@link DingTalkControllerSignatureTest} 的区别：
 * 那个类覆盖的是 {@code /api/dingtalk/approval-callback}（本就返回 401），
 * 本类覆盖的是 {@code /events}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "dingtalk.app-secret=testAppSecret123"
})
class DingTalkEventCallbackHttpStatusTest {

    private static final String APP_SECRET = "testAppSecret123";

    @Autowired
    private MockMvc mockMvc;

    /** 钉钉事件回调签名：Base64(HmacSHA256(appSecret, timestamp))，timestamp 为毫秒。 */
    private String sign(String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("events：缺签名头 → HTTP 401（不是 200）")
    void missingHeaders_returnsHttp401() throws Exception {
        mockMvc.perform(post("/api/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"approval_status_changed\",\"eventId\":\"e-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("events：签名错误 → HTTP 401（不是 200）")
    void wrongSignature_returnsHttp401() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        mockMvc.perform(post("/api/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DingTalk-Timestamp", ts)
                        .header("X-DingTalk-Signature", "definitely-wrong-signature")
                        .content("{\"eventType\":\"approval_status_changed\",\"eventId\":\"e-2\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("events：签名正确 → HTTP 200")
    void validSignature_returnsHttp200() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        mockMvc.perform(post("/api/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DingTalk-Timestamp", ts)
                        .header("X-DingTalk-Signature", sign(ts))
                        .content("{\"eventType\":\"approval_status_changed\",\"eventId\":\"e-valid-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
