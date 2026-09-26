package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.entity.ImMessageReactionEntity;
import com.nocobase.event.RecordChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** 消息控制器测试:覆盖 REST 契约与参数夹取。 */
class ImMessageControllerTest {

    private MessageService messageService;
    private ReactionService reactionService;
    private PinService pinService;
    private ApplicationEventPublisher eventPublisher;
    private ImMessageController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        reactionService = mock(ReactionService.class);
        pinService = mock(PinService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        controller = new ImMessageController(messageService, reactionService, pinService, eventPublisher);
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

    /**
     * 断链修复契约测试：GET /api/im/messages/search/cross 必须存在，
     * 且把 tenantId + userId 下传给 Service 做成员过滤（防越权）。
     */
    @Test
    void searchCross_delegatesWithTenantAndUser() {
        when(messageService.searchCrossChannel(eq("tenant_default"), eq(userId),
                eq(null), eq("预算"), anyInt())).thenReturn(List.of(message()));

        Map<String, Object> resp = controller.searchCross("预算", null, 20, user);

        assertThat(resp.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat((List<?>) data.get("messages")).hasSize(1);
        // 必须带租户与用户身份，否则搜到非成员频道内容
        verify(messageService).searchCrossChannel(eq("tenant_default"), eq(userId),
                eq(null), eq("预算"), eq(20));
    }

    /** 指定 channelId 时原样下传（Service 侧校验成员身份）。 */
    @Test
    void searchCross_withChannelId() {
        when(messageService.searchCrossChannel(anyString(), any(), eq(channelId),
                anyString(), anyInt())).thenReturn(List.of());

        controller.searchCross("预算", channelId, 20, user);

        verify(messageService).searchCrossChannel("tenant_default", userId, channelId, "预算", 20);
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
        UUID messageId = UUID.randomUUID();
        when(messageService.mustGet(messageId)).thenReturn(message());
        when(reactionService.list(messageId)).thenReturn(List.of());

        Map<String, Object> resp = controller.listReactions(messageId, user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    /**
     * 越权防护:非频道成员读取消息的表情回应必须 403。
     *
     * <p>该端点此前无任何鉴权参数,任意登录用户传 messageId 即可读取他租户消息的
     * reaction(userId 集合);现通过「消息 → 频道 → 成员」链路校验归属。
     */
    @Test
    void listReactions_nonMember_returns403() {
        UUID messageId = UUID.randomUUID();
        when(messageService.mustGet(messageId)).thenReturn(message());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "不是频道成员"))
                .when(messageService).assertMember(any(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listReactions(messageId, user));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
