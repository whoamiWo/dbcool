package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * AI 控制器契约测试 — 验证 /api/ai/chat 与 /api/ai/quota 端点存在且返回结构正确。
 */
class AiControllerTest {

    private AiAssistantService aiService;
    private AiController controller;

    private final AuthenticatedUser user = new AuthenticatedUser(
            UUID.randomUUID(), "testuser", "tenant_default");

    @BeforeEach
    void setUp() {
        aiService = mock(AiAssistantService.class);
        controller = new AiController(aiService);
    }

    @Test
    void chat_returnsStructure_whenPromptProvided() {
        when(aiService.chat(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Map.of(
                        "code", 0,
                        "message", "success",
                        "data", Map.of("result", "回复内容", "cached", false, "tokensUsed", 10)
                ));

        Map<String, Object> body = Map.of("prompt", "你好", "model", "gpt-4");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.chat(body, "Bearer token", user);

        assertThat(result.get("code")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("success");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("result")).isEqualTo("回复内容");
    }

    @Test
    void chat_returns400_whenPromptMissing() {
        Map<String, Object> body = Map.of("model", "gpt-4");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.chat(body, null, user);

        assertThat(result.get("code")).isEqualTo(400);
        assertThat(result.get("message")).isEqualTo("prompt 必填");
    }

    @Test
    void quota_returnsStructure_whenEnabled() {
        when(aiService.getQuota(anyString(), anyString()))
                .thenReturn(Map.of(
                        "code", 0,
                        "message", "success",
                        "data", Map.of("remaining", 80, "daily", 100, "monthly", 50000)
                ));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.quota(user, "Bearer token");

        assertThat(result.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("remaining")).isEqualTo(80);
    }

    @Test
    void quota_returnsZero_whenDisabled() {
        when(aiService.getQuota(eq(user.userId().toString()), eq(null)))
                .thenReturn(Map.of(
                        "code", 0,
                        "message", "AI 未启用",
                        "data", Map.of("remaining", 0, "daily", 0, "monthly", 0)
                ));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = controller.quota(user, null);

        assertThat(result.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("remaining")).isEqualTo(0);
    }
}
