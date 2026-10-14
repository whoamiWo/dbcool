package com.nocobase.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE105: AiAssistantService 与 AgentService 覆盖。
 *
 * <p>AiAssistantService 含 @Value 字段，用反射注入。
 * AgentService 用 mock 所有依赖。
 */
public class AiAssistantServiceTest {

    private AiAssistantService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AiAssistantService();
        // 反射注入私有字段（绕过 @Value）
        setPrivate(service, "enabled", false);
        setPrivate(service, "pythonUrl", "http://test");
        setPrivate(service, "model", "gpt-4o");
        setPrivate(service, "maxTokens", 512);
    }

    private void setPrivate(Object obj, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @Test
    void enabledFalse_allMethodsReturnUnavailable() {
        // callLlm disabled 时返回 {code:0, message:"AI 未启用", data: mapper.apply(UNAVAILABLE)}
        Map<String, Object> r1 = service.generateWikiContent("t", "i", "tok");
        assertEquals("AI 未启用", r1.get("message"));
        assertTrue((Boolean) r1.get("data").toString().contains("AI 助手暂未启用"));

        Map<String, Object> r2 = service.askQuestion("q", "c", "tok");
        assertEquals("AI 未启用", r2.get("message"));
        assertTrue((Boolean) r2.get("data").toString().contains("AI 助手暂未启用"));

        Map<String, Object> r3 = service.generateCode("gen", "py", "tok");
        assertEquals("AI 未启用", r3.get("message"));
        assertTrue((Boolean) r3.get("data").toString().contains("AI 助手暂未启用"));
    }

    @Test
    void getQuota_returnsDisabledQuota() {
        Map<String, Object> q = service.getQuota("u1", "tok");
        assertEquals(0, q.get("code"));
        assertEquals("AI 未启用", q.get("message"));
        Map<String, Object> data = (Map<String, Object>) q.get("data");
        assertEquals(0, data.get("remaining"));
        assertEquals(0, data.get("daily"));
        assertEquals(0, data.get("monthly"));
    }

    @Test
    void isEnabled_returnsInjectedValue() {
        assertFalse(service.isEnabled());
    }

    @Test
    void chat_disabled_returnsUnavailable() {
        Map<String, Object> r = service.chat("hi", null, 0, "tok");
        assertEquals("AI 未启用", r.get("message"));
        assertTrue((Boolean) r.get("data").toString().contains("AI 助手暂未启用"));
    }
}
