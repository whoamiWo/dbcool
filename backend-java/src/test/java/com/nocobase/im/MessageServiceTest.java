package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.realtime.RedisStompBridge;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MessageServiceTest {

    private ImMessageRepository messageRepo;
    private ImChannelMemberRepository memberRepo;
    private RedisStompBridge bridge;
    private MessageService service;

    private final UUID channelId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageRepo = mock(ImMessageRepository.class);
        memberRepo = mock(ImChannelMemberRepository.class);
        bridge = mock(RedisStompBridge.class);
        service = new MessageService(messageRepo, memberRepo, bridge);
    }

    @Test
    void send_broadcastsToChannelTopic() {
        when(memberRepo.existsByChannelIdAndUserId(channelId, senderId)).thenReturn(true);
        when(messageRepo.save(any(ImMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.send("tenant_default", channelId, senderId, "hello", "text", null);

        verify(messageRepo).save(any(ImMessageEntity.class));
        verify(bridge).broadcast(anyString(), any());
    }

    @Test
    void send_nonMember_isForbidden() {
        when(memberRepo.existsByChannelIdAndUserId(channelId, senderId)).thenReturn(false);

        assertThatThrownBy(() ->
                service.send("tenant_default", channelId, senderId, "hi", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void send_blankContent_isRejected() {
        when(memberRepo.existsByChannelIdAndUserId(channelId, senderId)).thenReturn(true);

        assertThatThrownBy(() ->
                service.send("tenant_default", channelId, senderId, "   ", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void edit_byOtherUser_isForbidden() {
        ImMessageEntity m = message(senderId);
        when(messageRepo.findById(m.getId())).thenReturn(Optional.of(m));

        UUID attacker = UUID.randomUUID();
        assertThatThrownBy(() -> service.edit("t", m.getId(), attacker, "篡改"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void edit_byOwner_setsEditedAt() {
        ImMessageEntity m = message(senderId);
        when(messageRepo.findById(m.getId())).thenReturn(Optional.of(m));
        when(messageRepo.save(any(ImMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ImMessageEntity edited = service.edit("t", m.getId(), senderId, "new content");

        assertThat(edited.getContent()).isEqualTo("new content");
        assertThat(edited.getEditedAt()).isNotNull();
    }

    @Test
    void delete_isSoftDelete() {
        ImMessageEntity m = message(senderId);
        when(messageRepo.findById(m.getId())).thenReturn(Optional.of(m));
        when(messageRepo.save(any(ImMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.delete("t", m.getId(), senderId);

        // 软删除:保留线程完整性,不物理删除
        assertThat(m.getDeletedAt()).isNotNull();
        verify(messageRepo).save(m);
    }

    @Test
    void unreadCount_nonMemberIsZero() {
        when(memberRepo.findByChannelIdAndUserId(channelId, senderId)).thenReturn(Optional.empty());
        assertThat(service.unreadCount(channelId, senderId)).isZero();
    }

    @Test
    void unreadCount_neverReadCountsAll() {
        ImChannelMemberEntity m = new ImChannelMemberEntity();
        m.setChannelId(channelId);
        m.setUserId(senderId);
        m.setLastReadMessageId(null); // 从未读过
        when(memberRepo.findByChannelIdAndUserId(channelId, senderId)).thenReturn(Optional.of(m));
        when(messageRepo.countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull(
                any(), any())).thenReturn(7L);

        assertThat(service.unreadCount(channelId, senderId)).isEqualTo(7L);
    }

    @Test
    void markRead_advancesCursor() {
        ImChannelMemberEntity m = new ImChannelMemberEntity();
        m.setChannelId(channelId);
        m.setUserId(senderId);
        when(memberRepo.findByChannelIdAndUserId(channelId, senderId)).thenReturn(Optional.of(m));

        UUID lastId = UUID.randomUUID();
        service.markRead(channelId, senderId, lastId);

        assertThat(m.getLastReadMessageId()).isEqualTo(lastId);
        assertThat(m.getLastReadAt()).isNotNull();
        verify(memberRepo).save(m);
    }

    private ImMessageEntity message(UUID owner) {
        ImMessageEntity m = new ImMessageEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(channelId);
        m.setSenderId(owner);
        m.setContent("原内容");
        return m;
    }
}
