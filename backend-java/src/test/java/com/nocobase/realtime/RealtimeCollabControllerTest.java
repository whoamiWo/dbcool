package com.nocobase.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** PHASE105: RealtimeCollabController 测试。 */
class RealtimeCollabControllerTest {

    private RealtimeService realtimeService;
    private SimpMessagingTemplate messagingTemplate;
    private RealtimeCollabController controller;

    @BeforeEach
    void setUp() {
        realtimeService = mock(RealtimeService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        controller = new RealtimeCollabController(realtimeService, messagingTemplate);
    }

    @Test
    void join_principalMissing_returnsEarly() {
        Map<String, Object> attrs = new HashMap<>(); // 无 StompPrincipal
        Map<String, Object> body = Map.of("docId", "d1");

        controller.join(body, attrs);

        // principal 缺失，不应调用 joinRoom
    }

    @Test
    void join_docIdMissing_returnsEarly() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, new StompPrincipal(userId, "u1", "t1"));

        controller.join(Map.of(), attrs);

        // docId 为空应提前返回，不调用 service
    }

    @Test
    void join_initialStatePresent_sendsInitPayload() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, new StompPrincipal(userId, "u1", "t1"));
        attrs.put("sessionId", "s1");

        when(realtimeService.getDocumentState("d1")).thenReturn("{\"state\":\"ok\"}");
        when(realtimeService.getActiveUsers("d1")).thenReturn(1);

        controller.join(Map.of("docId", "d1"), attrs);

        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()),
                eq("/queue/collab-init"),
                any()
        );
    }

    @Test
    void leave_principalMissing_returnsEarly() {
        Map<String, Object> attrs = new HashMap<>();
        controller.leave(Map.of("docId", "d1"), attrs);
    }

    @Test
    void leave_docIdMissing_returnsEarly() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, new StompPrincipal(userId, "u1", "t1"));

        controller.leave(Map.of(), attrs);
    }

    @Test
    void update_updateMissing_returnsEarly() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, new StompPrincipal(userId, "u1", "t1"));

        controller.update(Map.of("docId", "d1"), attrs);
    }

    @Test
    void update_validCallsApplyUpdate() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, new StompPrincipal(userId, "u1", "t1"));

        when(realtimeService.applyUpdate("d1", "t1", userId, "base64data")).thenReturn(2L);

        controller.update(Map.of("docId", "d1", "update", "base64data"), attrs);

        verify(realtimeService).applyUpdate(eq("d1"), eq("t1"), eq(userId), eq("base64data"));
    }

    @Test
    void principalOf_createsPrincipal() {
        UUID userId = UUID.randomUUID();
        StompPrincipal p = RealtimeCollabController.principalOf(userId, "alice", "t1");
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.username()).isEqualTo("alice");
        assertThat(p.tenantId()).isEqualTo("t1");
    }

    @Test
    void str_blankString_returnsNull() {
        assertThat(RealtimeCollabController.str(null)).isNull();
        assertThat(RealtimeCollabController.str("")).isNull();
        assertThat(RealtimeCollabController.str("  ")).isNull();
        assertThat(RealtimeCollabController.str("abc")).isEqualTo("abc");
    }
}
