package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE105: AiAssistantService 成功路径测试（反射注入 RestTemplate）。
 *
 * <p>{@code rest} 是 private final 字段，用 ReflectionTestUtils 替换为 mock，
 * 从而覆盖 enabled=true 的 LLM 调用路径、toSimpleHtml 各 markdown 分支、
 * stripBearer 前缀剥离，以及 getModel/getMaxTokens。
 */
class AiAssistantServiceHttpTest {

    private AiAssistantService service;
    private RestTemplate rest;

    @BeforeEach
    void setUp() {
        service = new AiAssistantService();
        // 注入 @Value 字段（启用 + 配置）
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pythonUrl", "http://python");
        ReflectionTestUtils.setField(service, "model", "gpt-test");
        ReflectionTestUtils.setField(service, "maxTokens", 1024);

        rest = mock(RestTemplate.class);
        // private final 字段替换为本例 mock
        ReflectionTestUtils.setField(service, "rest", rest);
    }

    /** 让 /api/ai/chat 返回指定正文。 */
    private void stubChatResponse(String text) {
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("response", text)));
    }

    @Test
    void isEnabledAndConfigGetters() {
        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getModel()).isEqualTo("gpt-test");
        assertThat(service.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    void generateWikiContent_success_returnsContentAndHtml() {
        stubChatResponse("# 标题\n\n正文段落");

        Map<String, Object> out = service.generateWikiContent("标题", "补充", "Bearer tok-123");

        assertThat(out.get("code")).isEqualTo(0);
        assertThat(out.get("message")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) out.get("data");
        assertThat(data.get("content")).isEqualTo("# 标题\n\n正文段落");
        // toSimpleHtml：标题转 <h1>、段落转 <p>、空行转 <br/>
        assertThat(data.get("html")).asString()
                .contains("<h1>").contains("<p>").contains("<br/>");
    }

    @Test
    void toSimpleHtml_headingLevels() {
        stubChatResponse("### 三级\n## 二级\n# 一级");

        Map<String, Object> out = service.generateWikiContent("t", null, "tok");
        String html = (String) ((Map<?, ?>) out.get("data")).get("html");

        assertThat(html).contains("<h3>").contains("<h2>").contains("<h1>");
    }

    @Test
    void toSimpleHtml_codeFence() {
        stubChatResponse("```\ncode line\n```");

        Map<String, Object> out = service.generateWikiContent("t", null, "tok");
        String html = (String) ((Map<?, ?>) out.get("data")).get("html");

        assertThat(html).contains("<pre><code>").contains("code line");
    }

    @Test
    void toSimpleHtml_plainParagraph() {
        stubChatResponse("普通文本");

        Map<String, Object> out = service.generateWikiContent("t", null, "tok");
        String html = (String) ((Map<?, ?>) out.get("data")).get("html");

        assertThat(html).isEqualTo("<p>普通文本</p>");
    }

    @Test
    void askQuestion_success_returnsAnswer() {
        stubChatResponse("这是答案");

        Map<String, Object> out = service.askQuestion("问题", null, "tok");

        Map<?, ?> data = (Map<?, ?>) out.get("data");
        assertThat(data.get("answer")).isEqualTo("这是答案");
    }

    @Test
    void askQuestion_withContext_includesContext() {
        stubChatResponse("基于上下文的答案");

        Map<String, Object> out = service.askQuestion("问题", "上下文内容", "tok");

        assertThat(((Map<?, ?>) out.get("data")).get("answer")).isEqualTo("基于上下文的答案");
    }

    @Test
    void generateCode_success_returnsCode() {
        stubChatResponse("System.out.println();");

        Map<String, Object> out = service.generateCode("打印", "java", "tok");

        assertThat(((Map<?, ?>) out.get("data")).get("code")).isEqualTo("System.out.println();");
    }

    @Test
    void chat_success_returnsResult() {
        stubChatResponse("聊天回复");

        Map<String, Object> out = service.chat("你好", null, 0, "tok");

        assertThat(((Map<?, ?>) out.get("data")).get("result")).isEqualTo("聊天回复");
    }

    @Test
    void getQuota_success_parsesPayload() {
        when(rest.getForEntity(anyString(), eq(Map.class), anyMap()))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "remaining", 900, "daily", 1000, "monthly", 5000)));

        Map<String, Object> out = service.getQuota("u1", "tok");

        assertThat(out.get("code")).isEqualTo(0);
        Map<?, ?> data = (Map<?, ?>) out.get("data");
        assertThat(data.get("remaining")).isEqualTo(900);
        assertThat(data.get("daily")).isEqualTo(1000);
        assertThat(data.get("monthly")).isEqualTo(5000);
    }

    @Test
    void getQuota_nullPayload_defaultsToZero() {
        when(rest.getForEntity(anyString(), eq(Map.class), anyMap()))
                .thenReturn(ResponseEntity.ok(null));

        Map<String, Object> out = service.getQuota("u1", "tok");

        Map<?, ?> data = (Map<?, ?>) out.get("data");
        assertThat(data.get("remaining")).isEqualTo(0);
    }

    @Test
    void getQuota_httpFails_degradesToZero() {
        when(rest.getForEntity(anyString(), eq(Map.class), anyMap()))
                .thenThrow(new RuntimeException("python down"));

        // 异常被 catch，返回全 0 而非抛出
        Map<String, Object> out = service.getQuota("u1", "tok");

        assertThat(out.get("code")).isEqualTo(0);
        assertThat(((Map<?, ?>) out.get("data")).get("remaining")).isEqualTo(0);
    }

    @Test
    void callLlm_httpThrows_degradesToUnavailable() {
        when(rest.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> out = service.chat("hi", null, 0, "tok");

        // 降级：message 为「AI 服务不可用」，data.result 为 UNAVAILABLE 提示
        assertThat(out.get("message")).isEqualTo("AI 服务不可用");
    }
}
