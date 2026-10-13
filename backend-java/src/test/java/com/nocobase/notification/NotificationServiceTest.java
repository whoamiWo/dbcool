package com.nocobase.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PHASE103 T1: NotificationService 核心逻辑覆盖（补齐 missed=9 行）。
 */
class NotificationServiceTest {

    private NotificationChannelRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationChannelRepository.class);
        // 提供 dummy dispatcher
        NotificationDispatcher dummy = mock(NotificationDispatcher.class);
        when(dummy.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        service = new NotificationService(repository, List.of(dummy));
    }

    @Test
    @DisplayName("fire 缺少租户 → 抛 FORBIDDEN")
    void fire_missingTenant_throws() {
        assertThatThrownBy(() -> service.fire("", "event", "u1", Map.of()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("租户上下文缺失");
    }

    @Test
    @DisplayName("fire 正常发送 → 调用 enabled channels")
    void fire_sendsToEnabledChannels() {
        UUID chId = UUID.randomUUID();
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(chId);
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEvents("im.mention");
        ch.setEnabled(true);
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));

        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));

        service = new NotificationService(repository, List.of(dispatcher));
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));

        var results = service.fire("t1", "im.mention", "u1", Map.of("k", "v"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        verify(dispatcher).send(eq(ch), eq("u1"), any());
    }

    @Test
    @DisplayName("fire 无对应 dispatcher → log warn 且跳过")
    void fire_noDispatcher_skips() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(UUID.randomUUID());
        ch.setType(NotificationChannelEntity.Type.WECHAT_WORK); // 没有对应 dispatcher
        ch.setEnabled(true);
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));

        var results = service.fire("t1", "event", "u1", Map.of());

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("fire 渠道异常 → 捕获并返回 error result")
    void fire_channelException_returnsError() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(UUID.randomUUID());
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEnabled(true);
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));

        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenThrow(new RuntimeException("channel down"));

        service = new NotificationService(repository, List.of(dispatcher));

        var results = service.fire("t1", "event", "u1", Map.of());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
    }

    @Test
    @DisplayName("matchesEvent: null eventName → 全部匹配")
    void matchesEvent_nullEvent() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEvents("a,b,c");
        // 通过 fire 调用验证：只要能走到 dispatcher.send 就说明匹配
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));
        service = new NotificationService(repository, List.of(dispatcher));

        var results = service.fire("t1", null, "u1", Map.of());

        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("matchesEvent: null channel.events → 全部匹配")
    void matchesEvent_emptyEvents() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEvents(null);
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));
        service = new NotificationService(repository, List.of(dispatcher));

        var results = service.fire("t1", "any.event", "u1", Map.of());

        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("matchesEvent: 精确匹配")
    void matchesEvent_exactMatch() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEvents("a,b,c");
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));
        service = new NotificationService(repository, List.of(dispatcher));

        var results = service.fire("t1", "b", "u1", Map.of());

        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("matchesEvent: 不匹配 → 跳过")
    void matchesEvent_noMatch() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEvents("a,b,c");
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));
        service = new NotificationService(repository, List.of(dispatcher));

        var results = service.fire("t1", "x", "u1", Map.of());

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("testSend: 租户不匹配 → 抛 FORBIDDEN")
    void testSend_tenantMismatch_throws() {
        UUID chId = UUID.randomUUID();
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(chId);
        ch.setTenantId("tenant_A");
        when(repository.findById(chId)).thenReturn(Optional.of(ch));

        assertThatThrownBy(() -> service.testSend(chId, "u1", Map.of(), "tenant_B"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Tenant mismatch");
    }

    @Test
    @DisplayName("testSend: channel not found → 抛 IllegalArgumentException")
    void testSend_notFound_throws() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testSend(UUID.randomUUID(), "u1", Map.of(), "t1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel not found");
    }

    @Test
    @DisplayName("createMentionNotification → 调用 fire")
    void createMentionNotification_callsFire() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(UUID.randomUUID());
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEnabled(true);
        when(repository.findEnabledByTenantId("t1")).thenReturn(List.of(ch));

        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        when(dispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(dispatcher.send(any(), any(), any())).thenReturn(new NotificationDispatcher.SendResult(true, "ok"));

        service = new NotificationService(repository, List.of(dispatcher));

        service.createMentionNotification("t1", "u2", UUID.randomUUID(), UUID.randomUUID(), "snippet");

        verify(dispatcher).send(any(), eq("u2"), any());
    }
}
