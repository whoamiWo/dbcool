package com.nocobase.integration.feishu;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE105: 覆盖 FeishuController 全部 5 个端点
 * (getAuthUrl / oauthCallback / eventsCallback / sendMessage / replyMessage)。
 */
class FeishuControllerTest {

    private FeishuAppService feishuAppService;
    private AuditService auditService;
    private FeishuController controller;

    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        feishuAppService = mock(FeishuAppService.class);
        auditService = mock(AuditService.class);
        controller = new FeishuController(feishuAppService, auditService);
    }

    @Test
    void getAuthUrl_returnsOk() {
        when(feishuAppService.getOAuthUrl()).thenReturn("https://feishu/oauth");

        ResponseEntity<Map<String, String>> resp = controller.getAuthUrl();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("authUrl")).isEqualTo("https://feishu/oauth");
    }

    @Test
    void oauthCallback_blankCode_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("  ", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("code 必填");
    }

    @Test
    void oauthCallback_success_returnsResult() throws Exception {
        when(feishuAppService.handleOAuthCallback("valid_code"))
                .thenReturn(Map.of("code", 0, "message", "ok"));

        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("valid_code", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void oauthCallback_serviceThrows_returns500() throws Exception {
        when(feishuAppService.handleOAuthCallback("bad_code"))
                .thenThrow(new RuntimeException("feishu down"));

        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("bad_code", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("飞书授权失败");
    }

    @Test
    void eventsCallback_emptyBody_returns400() {
        ResponseEntity<String> resp = controller.eventsCallback("  ", null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("empty body");
    }

    @Test
    void eventsCallback_urlVerification_returnsChallenge() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"ch_123\"}";

        ResponseEntity<String> resp = controller.eventsCallback(body, null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("ch_123");
        verify(auditService).log(anyString(), anyString(), anyString(),
                eq("feishu.events.challenge"), anyString(), anyString(), any(Map.class));
    }

    @Test
    void eventsCallback_invalidSignature_returns401() {
        String body = "{\"type\":\"im.message.receive_v1\",\"event_id\":\"e1\"}";
        when(feishuAppService.verifySignature("ts", "nc", "sig", body)).thenReturn(false);

        ResponseEntity<String> resp = controller.eventsCallback(body, "ts", "nc", "sig");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("invalid signature");
    }

    @Test
    void eventsCallback_validSignature_handlesEvent() {
        String body = "{\"type\":\"im.message.receive_v1\",\"event_id\":\"e2\"}";
        when(feishuAppService.verifySignature("ts", "nc", "sig", body)).thenReturn(true);

        ResponseEntity<String> resp = controller.eventsCallback(body, "ts", "nc", "sig");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("0");
        verify(feishuAppService).handleEvent(any(JsonNode.class));
    }

    @Test
    void sendMessage_missingParams_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(Map.of("open_id", "ou_1"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        resp = controller.sendMessage(Map.of("text", "hi"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sendMessage_notConfigured_returns400() {
        when(feishuAppService.isConfigured()).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp =
                controller.sendMessage(Map.of("open_id", "ou_1", "text", "hi"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("飞书应用未配置");
    }

    @Test
    void sendMessage_success_returnsResult() throws Exception {
        when(feishuAppService.isConfigured()).thenReturn(true);
        when(feishuAppService.sendMessage("ou_1", "hi")).thenReturn(Map.of("code", 0));

        ResponseEntity<Map<String, Object>> resp =
                controller.sendMessage(Map.of("open_id", "ou_1", "text", "hi"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void sendMessage_serviceThrows_returns500() throws Exception {
        when(feishuAppService.isConfigured()).thenReturn(true);
        when(feishuAppService.sendMessage(anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        ResponseEntity<Map<String, Object>> resp =
                controller.sendMessage(Map.of("open_id", "ou_1", "text", "hi"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("飞书发送异常");
    }

    @Test
    void replyMessage_missingParams_returns400() {
        // 缺 reply_to_message_id → 400
        ResponseEntity<Map<String, Object>> resp =
                controller.replyMessage(Map.of("open_id", "ou_1", "text", "x"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 缺 open_id → 400
        resp = controller.replyMessage(Map.of("reply_to_message_id", "m1", "text", "x"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void replyMessage_success_returnsResult() throws Exception {
        when(feishuAppService.isConfigured()).thenReturn(true);
        when(feishuAppService.replyMessage("ou_1", "m1", "x")).thenReturn(Map.of("code", 0));

        ResponseEntity<Map<String, Object>> resp = controller.replyMessage(
                Map.of("open_id", "ou_1", "reply_to_message_id", "m1", "text", "x"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void replyMessage_serviceThrows_returns500() throws Exception {
        when(feishuAppService.isConfigured()).thenReturn(true);
        when(feishuAppService.replyMessage(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("err"));

        ResponseEntity<Map<String, Object>> resp = controller.replyMessage(
                Map.of("open_id", "ou_1", "reply_to_message_id", "m1", "text", "x"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
