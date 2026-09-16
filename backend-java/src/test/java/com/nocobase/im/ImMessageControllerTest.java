package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.entity.ImMessageReactionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 消息控制器测试:覆盖 REST 契约与参数夹取。 */
class ImMessageControllerTest {

    private MessageService messageService;
    private ReactionService reactionService;
    private ImMessageController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        reactionService = mock(ReactionService.class);
        controller = new ImMessageController(messageService, reactionService);
    }

    @Test
    void list_returnsCursorPage() {
        when(messageService.list(eq(channelId), any(), anyInt())).thenReturn(List.of(message()));

        Map<String, Object> resp = controller.list(channelId, null, 50, user);

        assertThat(resp.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("limit")).isEqualTo(50);
        assertThat(data.get("has_more")).isEqualTo(false);
    }

    @Test
    void list_clampsExcessiveLimit() {
        when(messageService.list(any(), any(), anyInt())).thenReturn(List.of());

        controller.list(channelId, null, 9999, user);

        // limit 必须被夹到 100,避免深翻页拖垮 DB
        verify(messageService).list(eq(channelId), any(), eq(100));
    }

    @Test
    void send_returnsCreated() {
        when(messageService.send(anyString(), eq(channelId), eq(userId),
                anyString(), any(), any())).thenReturn(message());

        ResponseEntity<Map<String, Object>> resp =
                controller.send(Map.of("channelId", channelId.toString(), "content", "hi"), user);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void send_withParentId_passesThrough() {
        UUID parentId = UUID.randomUUID();
        when(messageService.send(anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(message());

        controller.send(Map.of(
                "channelId", channelId.toString(),
                "content", "reply",
                "parentId", parentId.toString()), user);

        verify(messageService).send("tenant_default", channelId, userId, "reply", null, parentId);
    }

    @Test
    void edit_returnsUpdatedDto() {
        when(messageService.edit(anyString(), any(), eq(userId), anyString())).thenReturn(message());

        Map<String, Object> resp = controller.edit(UUID.randomUUID(), Map.of("content", "new"), user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void delete_delegatesToService() {
        UUID id = UUID.randomUUID();

        Map<String, Object> resp = controller.delete(id, user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(messageService).delete("tenant_default", id, userId);
    }

    @Test
    void thread_returnsReplies() {
        when(messageService.thread(any())).thenReturn(List.of(message()));

        Map<String, Object> resp = controller.thread(UUID.randomUUID());

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void search_returnsHits() {
        when(messageService.search(eq(channelId), eq("kw"), anyInt())).thenReturn(List.of(message()));

        Map<String, Object> resp = controller.search(channelId, "kw", 20);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void unread_returnsCount() {
        when(messageService.unreadCount(channelId, userId)).thenReturn(7L);

        Map<String, Object> resp = controller.unread(channelId, user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void markRead_advancesCursor() {
        UUID lastId = UUID.randomUUID();

        Map<String, Object> resp = controller.markRead(Map.of(
                "channelId", channelId.toString(),
                "lastMessageId", lastId.toString()), user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(messageService).markRead(channelId, userId, lastId);
    }

    @Test
    void addReaction_returnsOk() {
        ImMessageReactionEntity r = new ImMessageReactionEntity();
        r.setMessageId(UUID.randomUUID());
        r.setEmoji("+1");
        when(reactionService.add(anyString(), any(), eq(userId), eq("+1"))).thenReturn(r);

        Map<String, Object> resp =
                controller.addReaction(UUID.randomUUID(), Map.of("emoji", "+1"), user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void removeReaction_returnsOk() {
        Map<String, Object> resp = controller.removeReaction(UUID.randomUUID(), "+1", user);

        assertThat(resp.get("code")).isEqualTo(0);
        verify(reactionService).remove(any(), eq(userId), eq("+1"));
    }

    @Test
    void listReactions_returnsList() {
        when(reactionService.list(any())).thenReturn(List.of());

        Map<String, Object> resp = controller.listReactions(UUID.randomUUID());

        assertThat(resp.get("code")).isEqualTo(0);
    }

    private ImMessageEntity message() {
        ImMessageEntity m = new ImMessageEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(channelId);
        m.setSenderId(userId);
        m.setContent("hi");
        m.setContentType("text");
        m.setCreatedAt(Instant.now());
        return m;
    }
}
