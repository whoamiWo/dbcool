package com.nocobase.integration.dingtalk;

import com.nocobase.ai.AiAssistantService;
import com.nocobase.integration.dingtalk.DingTalkAiCardService.SendCardResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4: 钉钉 AI 卡片服务单测(摘要降级 / 凭证缺失分支)。 */
@ExtendWith(MockitoExtension.class)
class DingTalkAiCardServiceTest {

    @Mock
    private DingTalkAppService appService;

    @Mock
    private AiAssistantService aiService;

    @Mock
    private RestTemplate restTemplate;

    private DingTalkAiCardService service;

    @BeforeEach
    void setUp() {
        service = new DingTalkAiCardService(appService, aiService, restTemplate);
    }

    @Test
    void summarize_aiEnabled_returnsSummary() {
        when(aiService.chat(nullable(String.class), nullable(String.class), anyInt(), nullable(String.class)))
                .thenReturn(Map.of("code", 0, "message", "ok",
                        "data", Map.of("result", "这是AI生成的摘要")));

        String s = service.summarize("很长很长的原文内容");
        assertEquals("这是AI生成的摘要", s);
    }

    @Test
    void summarize_longAiOutput_truncatedTo200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('x');
        when(aiService.chat(nullable(String.class), nullable(String.class), anyInt(), nullable(String.class)))
                .thenReturn(Map.of("data", Map.of("result", sb.toString())));

        String s = service.summarize("原文");
        assertEquals(200, s.length());
    }

    @Test
    void summarize_aiFails_degradesToTruncatedOriginal() {
        when(aiService.chat(nullable(String.class), nullable(String.class), anyInt(), nullable(String.class)))
                .thenThrow(new RuntimeException("AI 不可用"));

        String original = "A".repeat(500);
        String s = service.summarize(original);
        assertEquals(201, s.length()); // 200 + "…"
        assertTrue(s.endsWith("…"));
    }

    @Test
    void summarize_blankContent_returnsEmpty() {
        assertEquals("", service.summarize(null));
        assertEquals("", service.summarize(""));
        assertEquals("", service.summarize("   "));
    }

    @Test
    void sendCard_missingCredentials_failsWithoutThrow() {
        when(appService.getAppConfig()).thenReturn(Map.of("appKey", ""));
        when(appService.getAppSecret()).thenReturn("");

        SendCardResult r = service.sendCard("t", "c", "user1", null, null);
        assertFalse(r.success());
        assertTrue(r.detail().contains("token") || r.detail().contains("配置"));
    }

    /**
     * 核心回归：修复前 access_token 被塞进 request body，
     * x-acs-dingtalk-access-token 请求头从未发出（B4 假完成根因）。
     * 本用例断言 token 确实通过 HTTP header 传递，且接收人写入 userId。
     */
    @Test
    void sendCard_tokenPassedViaHeader() {
        when(appService.getAppConfig()).thenReturn(Map.of("appKey", "appKey1"));
        when(appService.getAppSecret()).thenReturn("secret1");
        // gettoken 返回 token
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"tok_abc\"}");
        // 投递返回成功
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0,\"errmsg\":\"ok\"}", HttpStatus.OK));

        SendCardResult r = service.sendCard("标题", "正文", "user1", null, null);
        assertTrue(r.success(), "投递应成功: " + r.detail());

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));

        HttpEntity<?> sent = captor.getValue();
        assertEquals("tok_abc", sent.getHeaders().getFirst("x-acs-dingtalk-access-token"),
                "access_token 必须通过 x-acs-dingtalk-access-token 请求头传递");
        assertNotNull(sent.getHeaders().getContentType(), "必须设置 Content-Type");

        // 接收人写入配置的 recipient 字段（默认 userId，修正原 openConversationId 语义错）
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) sent.getBody();
        assertNotNull(body, "请求体不能为空");
        assertEquals("user1", String.valueOf(body.get("userId")));
        assertTrue(String.valueOf(body.get("outTrackId")).startsWith("aicard_"));
    }

    /**
     * 核心回归：修复前不校验 errcode 直接返回 ok()，
     * 钉钉返回错误也报"投递成功"（B4 假完成）。
     */
    @Test
    void sendCard_errcodeNonZero_fails() {
        when(appService.getAppConfig()).thenReturn(Map.of("appKey", "appKey1"));
        when(appService.getAppSecret()).thenReturn("secret1");
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"tok_abc\"}");
        // 钉钉 200 响应但业务 errcode 非 0
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"errcode\":4000,\"errmsg\":\"invalid template\"}", HttpStatus.OK));

        SendCardResult r = service.sendCard("标题", "正文", "user1", null, null);
        assertFalse(r.success(), "errcode 非 0 必须判定为失败，修复前会错误地返回成功");
        assertTrue(r.detail().contains("4000"), "失败详情应含 errcode: " + r.detail());
    }

    /** HTTP 非 2xx 必须判定为失败。 */
    @Test
    void sendCard_httpError_fails() {
        when(appService.getAppConfig()).thenReturn(Map.of("appKey", "appKey1"));
        when(appService.getAppSecret()).thenReturn("secret1");
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"tok_abc\"}");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("unauthorized", HttpStatus.UNAUTHORIZED));

        SendCardResult r = service.sendCard("标题", "正文", "user1", null, null);
        assertFalse(r.success(), "HTTP 401 必须判定为失败");
        assertTrue(r.detail().contains("401"), "失败详情应含状态码: " + r.detail());
    }
}
