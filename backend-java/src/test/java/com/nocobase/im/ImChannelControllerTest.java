package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 频道控制器测试:覆盖频道 CRUD、直聊、成员与在线心跳。 */
class ImChannelControllerTest {

    private ChannelService channelService;
    private PresenceService presenceService;
    private ImChannelController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        presenceService = mock(PresenceService.class);
        controller = new ImChannelController(channelService, presenceService);
    }

    @Test
    void list_returnsMyChannels() {
        when(channelService.listMine("tenant_default", userId)).thenReturn(List.of(channel()));

        Map<String, Object> resp = controller.list(user);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat((List<?>) resp.get("data")).hasSize(1);
    }

    @Test
    void create_returnsCreated() {
        when(channelService.create(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(channel());

        ResponseEntity<Map<String, Object>> resp = controller.create(Map.of(
                "name", "general",
                "type", "PUBLIC",
                "memberIds", List.of(UUID.randomUUID().toString())), user);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void direct_isIdempotent() {
        UUID other = UUID.randomUUID();
        when(channelService.getOrCreateDirect("tenant_default", userId, other))
                .thenReturn(channel());

        Map<String, Object> resp = controller.direct(other, user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void get_returnsChannel() {
        when(channelService.mustGet("tenant_default", channelId)).thenReturn(channel());

        Map<String, Object> resp = controller.get(channelId, user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void members_includeOnlineStatus() {
        ImChannelMemberEntity m = new ImChannelMemberEntity();
        m.setChannelId(channelId);
        m.setUserId(userId);
        m.setRole("MEMBER");
        when(channelService.members(channelId)).thenReturn(List.of(m));
        when(presenceService.isOnline("tenant_default", userId)).thenReturn(true);

        Map<String, Object> resp = controller.members(channelId, user);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat((List<?>) resp.get("data")).hasSize(1);
    }

    @Test
    void join_defaultsToSelf() {
        Map<String, Object> resp = controller.join(channelId, null, user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(channelService).join("tenant_default", channelId, userId, null);
    }

    @Test
    void join_withExplicitUserId() {
        UUID target = UUID.randomUUID();

        controller.join(channelId, Map.of("userId", target.toString(), "role", "MEMBER"), user);

        verify(channelService).join("tenant_default", channelId, target, "MEMBER");
    }

    @Test
    void leave_returnsOk() {
        Map<String, Object> resp = controller.leave(channelId, user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(channelService).leave(channelId, userId);
    }

    @Test
    void heartbeat_refreshesPresence() {
        Map<String, Object> resp = controller.heartbeat(user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(presenceService).heartbeat("tenant_default", userId);
    }

    private ImChannelEntity channel() {
        ImChannelEntity c = new ImChannelEntity();
        c.setId(channelId);
        c.setName("general");
        c.setType("PUBLIC");
        c.setCreatedBy(userId);
        c.setTenantId("tenant_default");
        return c;
    }
}
