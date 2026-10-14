package com.nocobase.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE105: SlackController 测试（直接实例化 + mock 依赖，无需 Spring 上下文）。
 *
 * <p>private 的 verifySignature 经 eventsCallback 各分支间接覆盖：
 * 空 secret / 时间戳过期 / 签名不匹配 / 签名正确 / 异常。
 */
class SlackControllerTest {

    private SlackAppService appService;
    private RestTemplate rest;
    private SlackController controller;

    private static final String SECRET = "sign-secret";
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        appService = mock(SlackAppService.class);
        rest = mock(RestTemplate.class);
        controller = new SlackController(appService, rest);
        when(appService.getSigningSecret()).thenReturn(SECRET);
        when(appService.isConfigured()).thenReturn(true);
        when(appService.getBotToken()).thenReturn("xoxb-tok");
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), "u1", "t1");
    }

    /** 按 Slack 规范计算签名：v0=hex(HMAC-SHA256(secret, "v0:ts:body")) */
    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    // ── getAuthUrl ─────────────────────────────────────────
    @Test
    void getAuthUrl_returnsUrl() {
        when(appService.getOAuthUrl()).thenReturn("https://slack/oauth?x=1");

        ResponseEntity<Map<String, String>> resp = controller.getAuthUrl();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("authUrl")).isEqualTo("https://slack/oauth?x=1");
    }

    // ── oauthCallback ──────────────────────────────────────
    @Test
    void oauthCallback_blankCode_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);

        assertThat(controller.oauthCallback(null, null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void oauthCallback_success_returnsOk() throws Exception {
        when(appService.handleOAuthCallback("code"))
                .thenReturn(Map.of("code", 0, "message", "success"));

        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("code", user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void oauthCallback_serviceThrows_returns500() throws Exception {
        when(appService.handleOAuthCallback("code")).thenThrow(new RuntimeException("授权失败"));

        ResponseEntity<Map<String, Object>> resp = controller.oauthCallback("code", user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── eventsCallback ─────────────────────────────────────
    @Test
    void eventsCallback_nullBody_returns400() {
        assertThat(controller.eventsCallback(null, null, null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void eventsCallback_blankBody_returns400() {
        assertThat(controller.eventsCallback("   ", null, null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void eventsCallback_verification_missingHeaders_returns401() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c1\"}";

        ResponseEntity<String> resp = controller.eventsCallback(body, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsCallback_verification_badSignature_returns401() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c1\"}";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, "v0=wrong");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsCallback_verification_emptyChallenge_returns400() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"\"}";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, sign(ts, body));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void eventsCallback_verification_valid_returnsChallenge() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"challenge-xyz\"}";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, sign(ts, body));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("challenge-xyz");
    }

    @Test
    void eventsCallback_verification_expiredTimestamp_returns401() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c1\"}";
        String oldTs = String.valueOf(System.currentTimeMillis() / 1000 - 400); // 超过 5 分钟

        ResponseEntity<String> resp = controller.eventsCallback(body, oldTs, sign(oldTs, body));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsCallback_emptySigningSecret_returns401() {
        when(appService.getSigningSecret()).thenReturn(""); // 空 secret → 校验失败
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c1\"}";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, sign(ts, body));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsCallback_event_missingHeaders_returns401() {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";

        ResponseEntity<String> resp = controller.eventsCallback(body, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsCallback_event_validSignature_handlesEvent() {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"hi\"}}";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, sign(ts, body));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(appService).handleEvent(any(JsonNode.class));
    }

    @Test
    void eventsCallback_malformedJson_returns500() {
        String body = "{not-json";
        String ts = nowTs();

        ResponseEntity<String> resp = controller.eventsCallback(body, ts, sign(ts, body));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── sendMessage ────────────────────────────────────────
    @Test
    void sendMessage_missingChannelOrText_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "hi"); // 缺 channel

        assertThat(controller.sendMessage(body, user()).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.sendMessage(Map.of(), user()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void sendMessage_notConfigured_returns400() {
        when(appService.isConfigured()).thenReturn(false);
        Map<String, Object> body = Map.of("channel", "C1", "text", "hi");

        assertThat(controller.sendMessage(body, user()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void sendMessage_nullResponse_returns500() {
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(null);
        Map<String, Object> body = Map.of("channel", "C1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void sendMessage_apiNotOk_returns500() {
        ObjectNode r = om.createObjectNode();
        r.put("ok", false);
        r.put("error", "channel_not_found");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class))).thenReturn(r);
        Map<String, Object> body = Map.of("channel", "C1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("channel_not_found");
    }

    @Test
    void sendMessage_success_returnsTs() {
        ObjectNode r = om.createObjectNode();
        r.put("ok", true);
        r.put("ts", "1700000000.123");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class))).thenReturn(r);
        Map<String, Object> body = Map.of("channel", "C1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("ts")).isEqualTo("1700000000.123");
    }

    @Test
    void sendMessage_withThreadTs_includedInPayload() {
        ObjectNode r = om.createObjectNode();
        r.put("ok", true);
        r.put("ts", "1");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class))).thenReturn(r);
        Map<String, Object> body = new HashMap<>();
        body.put("channel", "C1");
        body.put("text", "hi");
        body.put("thread_ts", "1700000000.000");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void sendMessage_httpThrows_returns500() {
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("network"));
        Map<String, Object> body = Map.of("channel", "C1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── replyMessage ───────────────────────────────────────
    @Test
    void replyMessage_missingParams_returns400() {
        assertThat(controller.replyMessage(Map.of("channel", "C1"), user())
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void replyMessage_notConfigured_returns400() {
        when(appService.isConfigured()).thenReturn(false);
        Map<String, Object> body = Map.of("channel", "C1", "thread_ts", "1", "text", "hi");

        assertThat(controller.replyMessage(body, user()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void replyMessage_success_returnsOk() {
        ObjectNode r = om.createObjectNode();
        r.put("ok", true);
        r.put("ts", "2");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class))).thenReturn(r);
        Map<String, Object> body = Map.of("channel", "C1", "thread_ts", "1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.replyMessage(body, user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void replyMessage_nullResponse_returns500() {
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(null);
        Map<String, Object> body = Map.of("channel", "C1", "thread_ts", "1", "text", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.replyMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
