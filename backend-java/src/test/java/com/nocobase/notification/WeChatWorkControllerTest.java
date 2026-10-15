package com.nocobase.notification;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
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
import static org.mockito.Mockito.when;

/** PHASE105: WeChatWorkController 测试 (send + ping)。 */
class WeChatWorkControllerTest {

    private NotificationService notificationService;
    private WeChatWorkController controller;

    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", "tenant_default");

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        controller = new WeChatWorkController(notificationService);
    }

    @Test
    void send_emptyBody_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.send(Map.of(), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("请求体不能为空");
    }

    @Test
    void send_missingChannelId_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.send(Map.of("recipient", "r", "body", "hi"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void send_success_returns200() {
        UUID channelId = UUID.randomUUID();
        when(notificationService.testSend(eq(channelId), eq("r"), any(), eq(USER.tenantId())))
                .thenReturn(new NotificationDispatcher.SendResult(true, "ok"));

        ResponseEntity<Map<String, Object>> resp = controller.send(Map.of(
                "channelId", channelId.toString(), "recipient", "r",
                "title", "t", "body", "hi", "msgType", "text"
        ), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        assertThat(resp.getBody().get("message")).isEqualTo("sent");
    }

    @Test
    void send_failure_returns502() {
        UUID channelId = UUID.randomUUID();
        when(notificationService.testSend(eq(channelId), anyString(), any(), eq(USER.tenantId())))
                .thenReturn(new NotificationDispatcher.SendResult(false, "webhook 404"));

        ResponseEntity<Map<String, Object>> resp = controller.send(Map.of(
                "channelId", channelId.toString(), "recipient", "r", "body", "hi"
        ), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.valueOf(502));
        assertThat(resp.getBody().get("code")).isEqualTo(502);
    }

    @Test
    void ping_unreachableUrl_returnsUnreachable() {
        ResponseEntity<Map<String, Object>> resp = controller.ping("not-a-valid-url");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("message")).isEqualTo("unreachable");
    }
}
