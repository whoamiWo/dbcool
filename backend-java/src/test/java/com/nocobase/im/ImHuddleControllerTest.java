package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImHuddleEntity;
import com.nocobase.im.entity.ImHuddleParticipantEntity;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** PHASE105: ImHuddleController 单元测试（非集成）。 */
class ImHuddleControllerTest {

    private HuddleService huddleService;
    private ImHuddleController controller;

    @BeforeEach
    void setUp() {
        huddleService = mock(HuddleService.class);
        controller = new ImHuddleController(huddleService);
    }

    private AuthenticatedUser mockUser(String tenantId, UUID userId) {
        // 注意字段顺序：AuthenticatedUser(UUID userId, String username, String tenantId)
        // 若把 tenantId 填到第 2 位，user.tenantId() 会取到 username，导致 mock 参数不匹配。
        return new AuthenticatedUser(userId, "user", tenantId);
    }

    private ImHuddleEntity mockHuddle(UUID id, UUID channelId, String name, String status) {
        ImHuddleEntity e = new ImHuddleEntity();
        e.setId(id);
        e.setChannelId(channelId);
        e.setName(name);
        e.setStatus(status);
        e.setCreatedAt(Instant.now());
        e.setEndedAt(null);
        e.setTenantId("t1");
        return e;
    }

    private ImHuddleParticipantEntity mockParticipant(UUID huddleId, UUID userId, boolean muted, boolean screenSharing) {
        ImHuddleParticipantEntity p = new ImHuddleParticipantEntity();
        p.setId(UUID.randomUUID());
        p.setHuddleId(huddleId);
        p.setUserId(userId);
        p.setIsMuted(muted);
        p.setIsScreenSharing(screenSharing);
        p.setJoinedAt(Instant.now());
        return p;
    }

    @Test
    void create_success() {
        UUID channelId = UUID.randomUUID();
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleEntity huddle = mockHuddle(huddleId, channelId, "test", "active");
        when(huddleService.create(any(), any(), any(), any())).thenReturn(huddle);

        ResponseEntity<Map<String, Object>> resp = controller.create(
                Map.of("channelId", channelId.toString(), "name", "test"),
                mockUser(tenantId, userId)
        );

        assertThat(resp.getStatusCodeValue()).isEqualTo(201);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(((Map<?, ?>) body.get("data")).get("id")).isEqualTo(huddleId.toString());
    }

    @Test
    void join_success() {
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleParticipantEntity participant = mockParticipant(huddleId, userId, false, false);
        when(huddleService.join(any(), any(), any())).thenReturn(participant);

        ResponseEntity<Map<String, Object>> resp = controller.join(huddleId, mockUser(tenantId, userId));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(((Map<?, ?>) body.get("data")).get("huddleId")).isEqualTo(huddleId.toString());
    }

    @Test
    void leave_success() {
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        doNothing().when(huddleService).leave(eq(huddleId), eq(userId), eq(tenantId));

        ResponseEntity<Map<String, Object>> resp = controller.leave(huddleId, mockUser(tenantId, userId));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void end_success() {
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleEntity huddle = mockHuddle(huddleId, UUID.randomUUID(), "x", "ended");
        huddle.setEndedAt(Instant.now());
        when(huddleService.end(eq(huddleId), eq(userId), eq(tenantId))).thenReturn(huddle);

        ResponseEntity<Map<String, Object>> resp = controller.end(huddleId, mockUser(tenantId, userId));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status")).isEqualTo("ended");
    }

    @Test
    void toggleMute_success() {
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleParticipantEntity p = mockParticipant(huddleId, userId, true, false);
        when(huddleService.toggleMute(eq(huddleId), eq(userId), eq(tenantId))).thenReturn(p);

        ResponseEntity<Map<String, Object>> resp = controller.toggleMute(huddleId, mockUser(tenantId, userId));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("muted")).isEqualTo(true);
    }

    @Test
    void toggleScreenShare_success() {
        UUID huddleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleParticipantEntity p = mockParticipant(huddleId, userId, false, true);
        when(huddleService.toggleScreenShare(eq(huddleId), eq(userId), eq(tenantId))).thenReturn(p);

        ResponseEntity<Map<String, Object>> resp = controller.toggleScreenShare(huddleId, mockUser(tenantId, userId));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("screenSharing")).isEqualTo(true);
    }

    @Test
    void listActive_success() {
        UUID channelId = UUID.randomUUID();
        String tenantId = "t1";
        List<ImHuddleEntity> huddles = Collections.singletonList(mockHuddle(UUID.randomUUID(), channelId, "x", "active"));
        when(huddleService.listActive(eq(tenantId), eq(channelId))).thenReturn(huddles);

        ResponseEntity<Map<String, Object>> resp = controller.listActive(channelId, mockUser(tenantId, UUID.randomUUID()));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("huddles")).isInstanceOf(List.class);
    }

    @Test
    void get_success() {
        UUID huddleId = UUID.randomUUID();
        String tenantId = "t1";
        ImHuddleEntity huddle = mockHuddle(huddleId, UUID.randomUUID(), "x", "active");
        when(huddleService.get(eq(huddleId), eq(tenantId))).thenReturn(huddle);

        ResponseEntity<Map<String, Object>> resp = controller.get(huddleId, mockUser(tenantId, UUID.randomUUID()));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("data")).isSameAs(huddle);
    }

    @Test
    void listParticipants_success() {
        UUID huddleId = UUID.randomUUID();
        String tenantId = "t1";
        List<ImHuddleParticipantEntity> participants = Collections.singletonList(mockParticipant(huddleId, UUID.randomUUID(), false, false));
        when(huddleService.listParticipants(eq(huddleId), eq(tenantId))).thenReturn(participants);

        ResponseEntity<Map<String, Object>> resp = controller.listParticipants(huddleId, mockUser(tenantId, UUID.randomUUID()));

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("participants")).isInstanceOf(List.class);
    }
}
