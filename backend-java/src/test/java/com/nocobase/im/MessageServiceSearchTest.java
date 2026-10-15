package com.nocobase.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.parser.RichTextParser;
import com.nocobase.notification.NotificationService;
import com.nocobase.realtime.RedisStompBridge;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * MessageService list/search/searchCrossChannel 补充测试（PHASE108 冲 85%）。
 */
class MessageServiceSearchTest {

    private ImMessageRepository messageRepository;
    private ImChannelMemberRepository memberRepository;
    private RedisStompBridge bridge;
    private RichTextParser parser;
    private NotificationService notificationService;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ImMessageRepository.class);
        memberRepository = mock(ImChannelMemberRepository.class);
        bridge = mock(RedisStompBridge.class);
        parser = mock(RichTextParser.class);
        notificationService = mock(NotificationService.class);
        service = new MessageService(messageRepository, memberRepository, bridge, parser, notificationService);
    }

    @Test
    void list_nullCursor_usesBaseQuery() {
        UUID channelId = UUID.randomUUID();
        when(messageRepository.findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(eq(channelId), any(PageRequest.class)))
                .thenReturn(List.of());

        List<ImMessageEntity> r = service.list(channelId, null, 20);

        assertEquals(0, r.size());
    }

    @Test
    void list_withCursor_usesCursorQuery() {
        UUID channelId = UUID.randomUUID();
        Instant cursor = Instant.now();
        when(messageRepository.findByChannelIdAndParentIdIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
                eq(channelId), eq(cursor), any(PageRequest.class)))
                .thenReturn(List.of());

        List<ImMessageEntity> r = service.list(channelId, cursor, 20);

        assertEquals(0, r.size());
    }

    @Test
    void search_blankKeyword_returnsEmpty() {
        assertEquals(0, service.search(UUID.randomUUID(), "  ", 10).size());
    }

    @Test
    void search_withKeyword_queries() {
        UUID channelId = UUID.randomUUID();
        when(messageRepository.search(eq(channelId), eq("hi"), any(PageRequest.class)))
                .thenReturn(List.of());

        assertEquals(0, service.search(channelId, "hi", 10).size());
    }

    @Test
    void searchCrossChannel_blankKeyword_returnsEmpty() {
        assertEquals(0, service.searchCrossChannel("t1", UUID.randomUUID(), null, " ", 10).size());
    }

    @Test
    void searchCrossChannel_channelSpecifiedNotMember_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memberRepository.existsByChannelIdAndUserId(eq(channelId), eq(userId))).thenReturn(false);

        assertEquals(0, service.searchCrossChannel("t1", userId, channelId, "word", 10).size());
    }

    @Test
    void searchCrossChannel_noJoinedChannels_returnsEmpty() {
        UUID userId = UUID.randomUUID();
        when(memberRepository.findByTenantIdAndUserId(eq("t1"), eq(userId))).thenReturn(List.of());

        assertEquals(0, service.searchCrossChannel("t1", userId, null, "word", 10).size());
    }

    @Test
    void searchCrossChannel_returnsFilteredMessages() {
        UUID userId = UUID.randomUUID();
        UUID channel1 = UUID.randomUUID();
        ImChannelMemberEntity member = new ImChannelMemberEntity();
        member.setChannelId(channel1);
        when(memberRepository.findByTenantIdAndUserId(eq("t1"), eq(userId)))
                .thenReturn(List.of(member));

        ImMessageEntity msg = new ImMessageEntity();
        msg.setChannelId(channel1);
        when(messageRepository.searchCrossChannel(eq("t1"), ArgumentMatchers.<UUID>any(), eq("word"), any(PageRequest.class)))
                .thenReturn(List.of(msg));

        List<ImMessageEntity> r = service.searchCrossChannel("t1", userId, null, "word", 10);

        assertEquals(1, r.size());
        assertEquals(channel1, r.get(0).getChannelId());
    }
}
