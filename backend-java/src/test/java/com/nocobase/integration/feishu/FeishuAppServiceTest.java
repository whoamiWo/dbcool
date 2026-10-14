package com.nocobase.integration.feishu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE105: FeishuAppService 覆盖（凭证解析、签名校验、OAuth、消息）。
 */
public class FeishuAppServiceTest {

    private FeishuAppService service;

    @BeforeEach
    void setUp() {
        // 默认 mock 凭证服务返回空（走全局 @Value 回退）
        TenantCredentialService credSvc = mock(TenantCredentialService.class);
        when(credSvc.get(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        service = new FeishuAppService(credSvc);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = FeishuAppService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void mockRest(FeishuAppService svc, String tokenForAuth, JsonNode msgResponse) throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    if (url.contains("/auth/v3/tenant_access_token/internal")) {
                        JsonNode body = new ObjectMapper().createObjectNode()
                                .put("code", 0)
                                .put("tenant_access_token", tokenForAuth)
                                .put("expire_in", 7200);
                        return ResponseEntity.ok(body);
                    }
                    return ResponseEntity.ok(msgResponse);
                });
        java.lang.reflect.Field f = FeishuAppService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(svc, rest);
    }

    // ── isConfigured ───────────────────────────────────────
    @Test
    void isConfigured_globalSet_true() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        assertTrue(service.isConfigured());
    }

    @Test
    void isConfigured_missingSecret_false() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "");
        assertFalse(service.isConfigured());
    }

    // 注：isConfigured() 内部调用 resolveForKey(key, null)，tenantId 为 null 时
    // 按设计**跳过** credentialService 只走全局 @Value 回退（见 resolveForKey 实现）。
    // 因此"租户级凭证使 isConfigured 为 true"这条路在 null 租户下不可达，
    // 租户级读取的正确性由 TenantCredentialServiceTest 覆盖，此处不重复。

    // ── getOAuthUrl ────────────────────────────────────────
    @Test
    void getOAuthUrl_missingAppId_throws() {
        assertThrows(IllegalStateException.class, () -> service.getOAuthUrl());
    }

    @Test
    void getOAuthUrl_hasAppId_returnsUrl() throws Exception {
        set(service, "appId", "aid");
        set(service, "redirectUri", "https://x/callback");
        String url = service.getOAuthUrl();
        assertTrue(url.contains("app_id=aid"));
    }

    // ── handleOAuthCallback ────────────────────────────────
    @Test
    void handleOAuthCallback_success_returnsToken() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        JsonNode fake = new ObjectMapper().createObjectNode()
                .put("code", 0).put("tenant_access_token", "tok123").put("expire_in", 7200);
        mockRest(service, "tok123", fake);
        Map<String, Object> r = service.handleOAuthCallback("code1");
        assertEquals(0, r.get("code"));
        assertEquals("tok123", ((Map) r.get("data")).get("tenant_access_token"));
    }

    @Test
    void handleOAuthCallback_missingToken_throws() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().createObjectNode()
                        .put("code", 0))); // 缺 tenant_access_token
        java.lang.reflect.Field f = FeishuAppService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(service, rest);
        assertThrows(RuntimeException.class, () -> service.handleOAuthCallback("code1"));
    }

    // ── handleEvent ────────────────────────────────────────
    @Test
    void handleEvent_messageType_noException() {
        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode evt = om.createObjectNode();
        evt.put("type", "message");
        com.fasterxml.jackson.databind.node.ObjectNode inner = om.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode msg = om.createObjectNode();
        msg.set("content", om.createObjectNode().put("text", "hi"));
        msg.put("open_id", "ou1");
        msg.put("message_id", "m1");
        inner.set("message", msg);
        evt.set("event", inner);
        service.handleEvent(evt);
    }

    @Test
    void handleEvent_otherType_noException() {
        service.handleEvent(new ObjectMapper().createObjectNode().put("type", "pong"));
    }

    // ── verifySignature ────────────────────────────────────
    @Test
    void verifySignature_missingParam_returnsFalse() {
        assertFalse(service.verifySignature(null, "n", "sig", "body"));
    }

    @Test
    void verifySignature_noEncryptKey_returnsFalse() throws Exception {
        set(service, "encryptKey", "");
        assertFalse(service.verifySignature("1", "n", "sig", "body"));
    }

    @Test
    void verifySignature_match_returnsTrue() throws Exception {
        set(service, "encryptKey", "key123");
        String ts = "1699999999";
        String nonce = "abc";
        String body = "msg";
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest((ts + nonce + "key123" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sig = java.util.HexFormat.of().formatHex(h);
        assertTrue(service.verifySignature(ts, nonce, sig, body));
    }

    @Test
    void verifySignature_mismatch_returnsFalse() throws Exception {
        set(service, "encryptKey", "key123");
        assertFalse(service.verifySignature("1", "n", "wrong", "body"));
    }

    // ── sendMessage / replyMessage ─────────────────────────
    @Test
    void sendMessage_success() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        JsonNode fake = new ObjectMapper().createObjectNode()
                .put("code", 0).set("data", new ObjectMapper().createObjectNode().put("message_id", "mid1"));
        mockRest(service, "tok", fake);
        Map<String, Object> r = service.sendMessage("ou1", "hi");
        assertEquals(0, r.get("code"));
    }

    @Test
    void sendMessage_apiError_throws() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        JsonNode fake = new ObjectMapper().createObjectNode().put("code", 999);
        mockRest(service, "tok", fake);
        assertThrows(RuntimeException.class, () -> service.sendMessage("ou1", "hi"));
    }

    @Test
    void replyMessage_success() throws Exception {
        set(service, "appId", "aid");
        set(service, "appSecret", "sec");
        JsonNode fake = new ObjectMapper().createObjectNode()
                .put("code", 0).set("data", new ObjectMapper().createObjectNode().put("message_id", "mid2"));
        mockRest(service, "tok", fake);
        Map<String, Object> r = service.replyMessage("ou1", "orig", "reply");
        assertEquals(0, r.get("code"));
    }
}
