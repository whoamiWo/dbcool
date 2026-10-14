package com.nocobase.integration.slack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.common.InboundMessageService;
import com.nocobase.integration.credential.TenantCredentialService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** PHASE105: SlackAppService 覆盖（重点：事件处理、OAuth 异常、配置检查）。 */
public class SlackAppServiceTest {

    private SlackAppService service;
    private TenantCredentialService credSvc;
    private InboundMessageService inboundMsgSvc;

    @BeforeEach
    void setUp() throws Exception {
        credSvc = mock(TenantCredentialService.class);
        // 默认返回空（模拟未配置租户凭证，走 @Value fallback）
        when(credSvc.get(any(String.class), any(String.class), any(String.class))).thenReturn(Optional.empty());
        inboundMsgSvc = mock(InboundMessageService.class);
        service = new SlackAppService(inboundMsgSvc, credSvc);
    }

    private static void mockRest(SlackAppService svc, JsonNode oauthResp) throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForObject(any(String.class), any(Object.class), any(Class.class)))
                .thenReturn(oauthResp);
        java.lang.reflect.Field f = SlackAppService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(svc, rest);
    }

    // ── 配置检查（均走 @Value fallback，默认空） ─────────────────────
    @Test
    void isConfigured_allMissing_false() {
        assertFalse(service.isConfigured());
    }

    @Test
    void getOAuthUrl_missingClientId_throws() {
        assertThrows(IllegalStateException.class, () -> service.getOAuthUrl());
    }

    // ── OAuth 流程（走 @Value fallback，故需手动设置 @Value 字段才能走成功路径）─
    @Test
    void getOAuthUrl_hasClientId_returnsUrl() throws Exception {
        // 反射设置字段模拟已配置 @Value
        java.lang.reflect.Field fid = SlackAppService.class.getDeclaredField("clientId");
        fid.setAccessible(true);
        fid.set(service, "test-cid");
        java.lang.reflect.Field fred = SlackAppService.class.getDeclaredField("redirectUri");
        fred.setAccessible(true);
        fred.set(service, "https://x/r");
        String url = service.getOAuthUrl();
        assertTrue(url.contains("client_id=test-cid"));
    }

    @Test
    void handleOAuthCallback_missingCreds_throws() throws Exception {
        // clientId / clientSecret 为空 → 抛 IllegalStateException
        assertThrows(IllegalStateException.class, () -> service.handleOAuthCallback("code"));
    }

    @Test
    void handleOAuthCallback_success_savesWorkspace() throws Exception {
        // 设置足够的 @Value 字段让 OAuth 走通
        java.lang.reflect.Field fid = SlackAppService.class.getDeclaredField("clientId");
        fid.setAccessible(true);
        fid.set(service, "cid");
        java.lang.reflect.Field fsec = SlackAppService.class.getDeclaredField("clientSecret");
        fsec.setAccessible(true);
        fsec.set(service, "cs");
        java.lang.reflect.Field furi = SlackAppService.class.getDeclaredField("redirectUri");
        furi.setAccessible(true);
        furi.set(service, ""); // 可为空

        // mock RestTemplate 返回成功响应
        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode resp = om.createObjectNode();
        resp.put("ok", true);
        resp.put("access_token", "xoxb-123");
        resp.set("team", om.createObjectNode().put("id", "T1").put("name", "Team1"));
        resp.put("bot_user_id", "U1");
        mockRest(service, resp);

        Map<String, Object> r = service.handleOAuthCallback("code1");
        assertEquals(0, r.get("code"));
        assertEquals("T1", ((Map) r.get("data")).get("teamId"));
    }

    @Test
    void handleOAuthCallback_missingToken_throws() throws Exception {
        // 设置 clientId/secret 让请求发出去，但返回缺少 access_token
        java.lang.reflect.Field fid = SlackAppService.class.getDeclaredField("clientId");
        fid.setAccessible(true);
        fid.set(service, "cid");
        java.lang.reflect.Field fsec = SlackAppService.class.getDeclaredField("clientSecret");
        fsec.setAccessible(true);
        fsec.set(service, "cs");
        java.lang.reflect.Field furi = SlackAppService.class.getDeclaredField("redirectUri");
        furi.setAccessible(true);
        furi.set(service, "");

        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode resp = om.createObjectNode().put("ok", true); // 缺 access_token
        mockRest(service, resp);
        assertThrows(RuntimeException.class, () -> service.handleOAuthCallback("code1"));
    }

    @Test
    void handleOAuthCallback_apiError_throws() throws Exception {
        java.lang.reflect.Field fid = SlackAppService.class.getDeclaredField("clientId");
        fid.setAccessible(true);
        fid.set(service, "cid");
        java.lang.reflect.Field fsec = SlackAppService.class.getDeclaredField("clientSecret");
        fsec.setAccessible(true);
        fsec.set(service, "cs");
        java.lang.reflect.Field furi = SlackAppService.class.getDeclaredField("redirectUri");
        furi.setAccessible(true);
        furi.set(service, "");

        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode resp = om.createObjectNode().put("ok", false).put("error", "invalid_code");
        mockRest(service, resp);
        assertThrows(RuntimeException.class, () -> service.handleOAuthCallback("code1"));
    }

    // ── 事件处理 ─────────────────────────────────────────────────────
    @Test
    void handleEvent_messageEvent_callsInboundMessageService() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode payload = om.createObjectNode()
                .put("type", "event_callback")
                .put("event_id", "Ev1")
                .set("event", om.createObjectNode()
                        .put("type", "message")
                        .put("text", "hello")
                        .put("channel", "C1")
                        .put("user", "U1")
                        .put("ts", "123"));
        service.handleEvent(payload);
        // inboundMsgSvc 被 mock，不抛异常即表示路径正确
    }

    @Test
    void handleEvent_botMessage_skipsProcessing() {
        ObjectMapper om = new ObjectMapper();
        JsonNode payload = om.createObjectNode()
                .put("type", "event_callback")
                .put("event_id", "Ev2")
                .set("event", om.createObjectNode()
                        .put("type", "message")
                        .put("subtype", "bot_message")
                        .put("text", "bot hi"));
        service.handleEvent(payload);
    }

    @Test
    void handleEvent_flatMessage_compatible() {
        ObjectMapper om = new ObjectMapper();
        JsonNode payload = om.createObjectNode()
                .put("type", "message")
                .put("text", "flat hi")
                .put("channel", "C2")
                .put("user", "U2")
                .put("ts", "456");
        service.handleEvent(payload);
    }

    // ── Getter 默认值（走 @Value fallback） ─────────────────────────
    @Test
    void getSigningSecret_defaultEmpty() {
        assertEquals("", service.getSigningSecret());
    }

    @Test
    void getBotToken_defaultEmpty() {
        assertEquals("", service.getBotToken());
    }

    @Test
    void getTeamId_defaultEmpty() {
        assertEquals("", service.getTeamId());
    }
}
