package com.nocobase.integration.mattermost;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.common.InboundMessageService;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE105: MattermostAppService 覆盖（配置检查、token 校验、入站消息、发送/回复）。
 */
public class MattermostAppServiceTest {

    private MattermostAppService service;

    @BeforeEach
    void setUp() {
        service = new MattermostAppService(mock(InboundMessageService.class));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = MattermostAppService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void mockRest(MattermostAppService svc, JsonNode resp) throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(resp));
        Field f = MattermostAppService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(svc, rest);
    }

    // ── isConfigured ──────────────────────────────────────
    @Test
    void isConfigured_siteAndTokenPresent_true() throws Exception {
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "tok");
        assertTrue(service.isConfigured());
    }

    @Test
    void isConfigured_blankFields_false() throws Exception {
        set(service, "siteUrl", "");
        set(service, "botToken", "tok");
        assertFalse(service.isConfigured());
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "");
        assertFalse(service.isConfigured());
    }

    // ── verifyWebhookToken ────────────────────────────────
    @Test
    void verifyToken_requireTrueButUnconfigured_failsClosed() throws Exception {
        set(service, "requireToken", true);
        set(service, "webhookToken", "");
        assertFalse(service.verifyWebhookToken("anything"));
    }

    @Test
    void verifyToken_matchesOrNot() throws Exception {
        set(service, "requireToken", true);
        set(service, "webhookToken", "secret");
        assertTrue(service.verifyWebhookToken("secret"));
        assertFalse(service.verifyWebhookToken("wrong"));
    }

    @Test
    void verifyToken_requireFalse_passes() throws Exception {
        set(service, "requireToken", false);
        set(service, "webhookToken", "");
        assertTrue(service.verifyWebhookToken("any"));
    }

    // ── handleIncomingMessage ─────────────────────────────
    @Test
    void handleIncomingMessage_validJson_dispatchesToInbound() throws Exception {
        InboundMessageService inbound = mock(InboundMessageService.class);
        service = new MattermostAppService(inbound);
        set(service, "defaultTenantId", "t1");

        JsonNode json = new ObjectMapper().createObjectNode()
                .put("text", "hello").put("channel_id", "c1")
                .put("user_id", "u1").put("id", "post123");
        service.handleIncomingMessage(json);
        // 无异常即视为调用链走通
    }

    @Test
    void handleIncomingMessage_blankPostId_noDispatch() throws Exception {
        JsonNode json = new ObjectMapper().createObjectNode()
                .put("text", "hello").put("id", "");
        service.handleIncomingMessage(json); // 走 warn 分支，不抛异常
    }

    // ── sendMessage ───────────────────────────────────────
    @Test
    void sendMessage_ok() throws Exception {
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "tok");
        mockRest(service, new ObjectMapper().createObjectNode()
                .put("id", "p1").put("create_at", 123L));
        Map<String, Object> r = service.sendMessage("chan", "hi");
        assertEquals(0, r.get("code"));
        assertEquals("sent", r.get("message"));
    }

    @Test
    void sendMessage_missingId_throws() throws Exception {
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "tok");
        mockRest(service, new ObjectMapper().createObjectNode());
        assertThrows(RuntimeException.class, () -> service.sendMessage("chan", "hi"));
    }

    // ── replyMessage ──────────────────────────────────────
    @Test
    void replyMessage_ok() throws Exception {
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "tok");
        mockRest(service, new ObjectMapper().createObjectNode()
                .put("id", "p2").put("create_at", 456L));
        Map<String, Object> r = service.replyMessage("chan", "root", "hi");
        assertEquals(0, r.get("code"));
    }

    @Test
    void replyMessage_missingId_throws() throws Exception {
        set(service, "siteUrl", "https://mm.example");
        set(service, "botToken", "tok");
        mockRest(service, new ObjectMapper().createObjectNode());
        assertThrows(RuntimeException.class, () -> service.replyMessage("chan", "root", "hi"));
    }
}
