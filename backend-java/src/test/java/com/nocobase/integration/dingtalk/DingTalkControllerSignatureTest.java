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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 钉钉回调验签集成测试 — 真实 Controller + 真实签名计算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "dingtalk.app-secret=testAppSecret123"
})
class DingTalkControllerSignatureTest {

    @Autowired
    private MockMvc mockMvc;

    private String appSecret = "testAppSecret123";

    @Test
    @DisplayName("缺签名头 → 401")
    void noSignatureHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/dingtalk/approval-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instance_id\":\"test\",\"result\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("签名错误 → 401")
    void wrongSignature_returns401() throws Exception {
        long timestamp = System.currentTimeMillis();
        String wrongSign = "wrong-signature";

        mockMvc.perform(post("/api/dingtalk/approval-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instance_id\":\"test\",\"result\":\"approved\"}")
                        .header("X-DingTalk-Timestamp", String.valueOf(timestamp))
                        .header("X-DingTalk-Signature", wrongSign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("正确签名 → 200")
    void correctSignature_returns200() throws Exception {
        long timestamp = System.currentTimeMillis();
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(hash);

        mockMvc.perform(post("/api/dingtalk/approval-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instance_id\":\"test-instance\",\"result\":\"approved\"}")
                        .header("X-DingTalk-Timestamp", String.valueOf(timestamp))
                        .header("X-DingTalk-Signature", sign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("重复事件 ID → 幂等")
    void duplicateEventId_idempotent() throws Exception {
        long timestamp = System.currentTimeMillis();
        String eventId = "test-event-id-001";
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(hash);

        String body = "{\"instance_id\":\"" + eventId + "\",\"result\":\"approved\"}";

        // 第一次请求
        mockMvc.perform(post("/api/dingtalk/approval-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-DingTalk-Timestamp", String.valueOf(timestamp))
                        .header("X-DingTalk-Signature", sign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 第二次请求（相同 instance_id）
        mockMvc.perform(post("/api/dingtalk/approval-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-DingTalk-Timestamp", String.valueOf(timestamp))
                        .header("X-DingTalk-Signature", sign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
