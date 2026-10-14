package com.nocobase.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.auth.UserEntity;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** PHASE105: WeComController 测试（直接实例化 + mock 全部依赖）。 */
class WeComControllerTest {

    private WeComAppService appService;
    private RestTemplate rest;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private WeComController controller;

    @BeforeEach
    void setUp() {
        appService = mock(WeComAppService.class);
        rest = mock(RestTemplate.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        controller = new WeComController(appService, rest, jwtService, refreshTokenService, redis);
        when(appService.isConfigured()).thenReturn(true);
        when(appService.getCorpId()).thenReturn("corp-1");
        when(appService.getAgentId()).thenReturn("agent-1");
        when(jwtService.getAccessTtl()).thenReturn(Duration.ofSeconds(3600));
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), "alice", "t1");
    }

    private UserEntity userEntity(String name) {
        UserEntity e = new UserEntity();
        e.setId(UUID.randomUUID());
        e.setUsername(name);
        e.setTenantId("t1");
        e.setDisplayName(name + "显示名");
        return e;
    }

    // ── getAuthUrl ─────────────────────────────────────────
    @Test
    void getAuthUrl_returnsUrlAndState_bindsStateInRedis() {
        ResponseEntity<Map<String, String>> resp =
                controller.getAuthUrl("https://app/cb", user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("authUrl")).contains("corp-1").contains("agent-1");
        assertThat(resp.getBody().get("state")).isNotBlank();
        // state 绑定用户并设 10 分钟过期（防 CSRF）
        verify(ops).set(anyString(), eq("alice"), eq(10L), any());
    }

    @Test
    void getAuthUrl_nullUser_bindsAnonymous() {
        ResponseEntity<Map<String, String>> resp =
                controller.getAuthUrl("https://app/cb", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(ops).set(anyString(), eq("anonymous"), eq(10L), any());
    }

    // ── callback ───────────────────────────────────────────
    @Test
    void callback_blankCode_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.callback(Map.of("state", "s1"), user());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("message")).asString().contains("code 必填");
    }

    @Test
    void callback_blankState_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.callback(Map.of("code", "c1"), user());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("message")).asString().contains("state 必填");
    }

    @Test
    void callback_unknownState_returns401() {
        when(ops.get("wecom:state:s-unknown")).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp =
                controller.callback(Map.of("code", "c1", "state", "s-unknown"), user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void callback_validState_issuesTokensAndDeletesState() throws Exception {
        when(ops.get("wecom:state:s1")).thenReturn("alice");
        UserEntity ue = userEntity("wecom_u1");
        when(appService.loginFromWeCom("c1")).thenReturn(ue);
        when(jwtService.issueAccessToken(any(), anyString(), anyString())).thenReturn("jwt-abc");
        when(refreshTokenService.issue(any())).thenReturn("rt-abc");

        ResponseEntity<Map<String, Object>> resp =
                controller.callback(Map.of("code", "c1", "state", "s1"), user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("access_token")).isEqualTo("jwt-abc");
        assertThat(data.get("refresh_token")).isEqualTo("rt-abc");
        assertThat(data.get("token_type")).isEqualTo("Bearer");
        assertThat(data.get("expires_in")).isEqualTo(3600L);
        // 一次性使用：校验通过后删除防重放
        verify(redis).delete("wecom:state:s1");
    }

    @Test
    void callback_loginThrows_returns500() throws Exception {
        when(ops.get("wecom:state:s1")).thenReturn("alice");
        when(appService.loginFromWeCom("c1")).thenThrow(new RuntimeException("登录失败"));

        ResponseEntity<Map<String, Object>> resp =
                controller.callback(Map.of("code", "c1", "state", "s1"), user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── syncContacts ───────────────────────────────────────
    @Test
    void syncContacts_notConfigured_returns400() {
        when(appService.isConfigured()).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.syncContacts(user());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void syncContacts_success_returnsResult() {
        when(appService.syncContacts()).thenReturn(Map.of("code", 0, "users", 5));

        ResponseEntity<Map<String, Object>> resp = controller.syncContacts(user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void syncContacts_throws_returns500() {
        when(appService.syncContacts()).thenThrow(new RuntimeException("同步炸了"));

        ResponseEntity<Map<String, Object>> resp = controller.syncContacts(user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── sendMessage ────────────────────────────────────────
    @Test
    void sendMessage_missingParams_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.sendMessage(Map.of("content", "hi"), user());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void sendMessage_notConfigured_returns400() {
        when(appService.isConfigured()).thenReturn(false);
        Map<String, Object> body = Map.of("toUser", "u1", "content", "hi");

        assertThat(controller.sendMessage(body, user()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void sendMessage_success_returnsSent() throws Exception {
        when(appService.getAccessToken()).thenReturn("tok");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":0,\"errmsg\":\"ok\"}");
        Map<String, Object> body = Map.of("toUser", "u1", "content", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("errcode")).isEqualTo(0);
    }

    @Test
    void sendMessage_apiErrcode_returns500() throws Exception {
        when(appService.getAccessToken()).thenReturn("tok");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":40003,\"errmsg\":\"invalid user\"}");
        Map<String, Object> body = Map.of("toUser", "u1", "content", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("errcode")).isEqualTo(40003);
    }

    @Test
    void sendMessage_nullResponse_returns500() throws Exception {
        when(appService.getAccessToken()).thenReturn("tok");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(null); // → readTree("{}") → errcode 默认 -1 → 500
        Map<String, Object> body = Map.of("toUser", "u1", "content", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void sendMessage_httpThrows_returns500() throws Exception {
        when(appService.getAccessToken()).thenReturn("tok");
        when(rest.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("network"));
        Map<String, Object> body = Map.of("toUser", "u1", "content", "hi");

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(body, user());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
