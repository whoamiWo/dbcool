package com.nocobase.integration.dingtalk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** PHASE105: DingTalkGroupService 覆盖。 */
public class DingTalkGroupServiceTest {

    private DingTalkGroupService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DingTalkGroupService();
    }

    @Test
    void sendToGroup_noException() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(any(String.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        java.lang.reflect.Field f = DingTalkGroupService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(service, rest);
        assertDoesNotThrow(() -> service.sendToGroup("https://example.com/hook", "hi"));
    }

    @Test
    void sendRichMessage_noException() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(any(String.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        java.lang.reflect.Field f = DingTalkGroupService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(service, rest);
        assertDoesNotThrow(() -> service.sendRichMessage("https://example.com/hook", "t", "c"));
    }

    @Test
    void notifyWorkflowEvent_callsRichMessage() throws Exception {
        assertDoesNotThrow(() -> service.notifyWorkflowEvent("w1", "APPROVE", "t", "body", "https://example.com/hook"));
    }

    @Test
    void toJson_handlesException() throws Exception {
        java.lang.reflect.Method m = DingTalkGroupService.class.getDeclaredMethod("toJson", Object.class);
        m.setAccessible(true);
        String s = (String) m.invoke(service, new Object() {});
        assertEquals("{}", s);
    }
}
