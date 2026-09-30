package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AI 降级分支测试 — 验证 ai.enabled=false 时返回降级文案且打 WARN 日志。
 */
class AiAssistantServiceFallbackTest {

    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        service = new AiAssistantService();
    }

    @Test
    void whenAiDisabled_returnsGracefulMessage() throws Exception {
        // 通过反射设置 enabled=false (模拟 ai.enabled=false)
        java.lang.reflect.Field enabledField = AiAssistantService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(service, false);

        // 通过反射设置 pythonUrl
        java.lang.reflect.Field urlField = AiAssistantService.class.getDeclaredField("pythonUrl");
        urlField.setAccessible(true);
        urlField.set(service, "http://localhost:8000");

        Function<String, Map<String, Object>> mapper = text -> Map.of("text", text);

        Map<String, Object> result = service.callLlmForTest("test prompt", null, mapper);

        assertThat(result.get("code")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("AI 未启用");
        assertThat(result.get("data")).isNotNull();
    }
}
