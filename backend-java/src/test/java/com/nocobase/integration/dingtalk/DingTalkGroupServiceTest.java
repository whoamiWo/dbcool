package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** PHASE105: DingTalkGroupService 测试（反射注入 RestTemplate）。 */
class DingTalkGroupServiceTest {

    private DingTalkGroupService service;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        service = new DingTalkGroupService();
        restTemplate = mock(RestTemplate.class);
        // final 字段用 Spring 的 ReflectionTestUtils 注入
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    @Test
    void sendToGroup_success_invokesPost() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));

        service.sendToGroup("https://oapi.dingtalk.com/robot/send", "hello");

        verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), any(Class.class));
    }

    @Test
    void sendToGroup_httpThrows_catchesAndDoesNotReThrow() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("network down"));

        // 不应抛异常（内部 catch）
        service.sendToGroup("https://oapi.dingtalk.com/robot/send", "hello");

        verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), any(Class.class));
    }

    @Test
    void sendRichMessage_success_invokesPost() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        service.sendRichMessage("https://url", "标题", "正文");

        verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), any(Class.class));
    }

    @Test
    void sendRichMessage_throws_catches() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("boom"));

        service.sendRichMessage("https://url", "t", "c");
    }

    @Test
    void notifyWorkflowEvent_delegatesToRichMessage() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        service.notifyWorkflowEvent("wf-1", "APPROVE", "待审批", "内容", "https://url");

        verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), any(Class.class));
    }

    @Test
    void toJson_serializesMap() throws Exception {
        var m = ReflectionTestUtils.invokeMethod(service, "toJson", Map.of("k", "v"));
        assertThat((String) m).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void toJson_unserializable_returnsEmptyObject() throws Exception {
        // 自引用对象无法序列化 → 走 catch 返回 "{}"
        Object cyclic = new Object() {
            final Object self = this;
        };
        var out = ReflectionTestUtils.invokeMethod(service, "toJson", cyclic);
        assertThat((String) out).isEqualTo("{}");
    }
}
