package com.nocobase.integration.dingtalk;

import com.nocobase.ai.AiAssistantService;
import com.nocobase.integration.dingtalk.DingTalkAiCardService.SendCardResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/** B4: 钉钉 AI 卡片服务单测(摘要降级 / 凭证缺失分支)。 */
@ExtendWith(MockitoExtension.class)
class DingTalkAiCardServiceTest {

    @Mock
    private DingTalkAppService appService;

    @Mock
    private AiAssistantService aiService;

    private DingTalkAiCardService service;

    @BeforeEach
    void setUp() {
        service = new DingTalkAiCardService(appService, aiService);
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
}
