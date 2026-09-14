package com.nocobase.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NotificationService 单元测试(Week 22 抬红线).
 *
 * <p>覆盖 fire + testSend + matchesEvent 三部分。
 */
class NotificationServiceTest {

    private NotificationChannelRepository repo;
    private NotificationService service;

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        repo = mock(NotificationChannelRepository.class);
        NotificationDispatcher emailDispatcher = mock(NotificationDispatcher.class);
        when(emailDispatcher.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(emailDispatcher.send(any(), any(), any()))
                .thenReturn(NotificationDispatcher.SendResult.ok("mocked"));
        service = new NotificationService(repo, List.of(emailDispatcher));
    }

    /* === fire: dispatcher routing === */

    @Test
    void fire_noChannels_returnsEmptyList() {
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of());
        var results = service.fire(TENANT, "workflow.approve", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).isEmpty();
    }

    @Test
    void fire_routesToDispatcherByType() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "all", true);
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "any.event", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).detail()).isEqualTo("mocked");
    }

    @Test
    void fire_noDispatcherForType_skipsChannel() {
        var ch = makeChannel(NotificationChannelEntity.Type.DINGTALK, "no_dispatcher", true);
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "any", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).isEmpty();
    }

    @Test
    void fire_dispatcherThrows_exceptionCaughtAndErrorResult() {
        NotificationDispatcher throwing = mock(NotificationDispatcher.class);
        when(throwing.supportedType()).thenReturn(NotificationChannelEntity.Type.EMAIL);
        when(throwing.send(any(), any(), any())).thenThrow(new RuntimeException("network down"));
        NotificationService svc = new NotificationService(repo, List.of(throwing));
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "err_channel", true);
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = svc.fire(TENANT, "any", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).detail()).contains("RuntimeException").contains("network down");
    }

    /* === matchesEvent: events CSV 匹配 === */

    @Test
    void fire_nullEventName_matchesAll() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "all_events", true);
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, null, "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
    }

    @Test
    void fire_blankEventName_matchesAll() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "all_events", true);
        ch.setEvents("");
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "  ", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
    }

    @Test
    void fire_eventMatchesExact_inCsv() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "matched", true);
        ch.setEvents("workflow.approve,workflow.reject,record.create");
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "workflow.approve", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
    }

    @Test
    void fire_eventNotInCsv_skipped() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "skipped", true);
        ch.setEvents("workflow.approve,workflow.reject");
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "record.create", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).isEmpty();
    }

    @Test
    void fire_eventCaseInsensitive() {
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "ci", true);
        ch.setEvents("Workflow.Approve");
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(ch));
        var results = service.fire(TENANT, "workflow.approve", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(1);
    }

    @Test
    void fire_mixedChannels_someMatchSomeNot() {
        var emailCh = makeChannel(NotificationChannelEntity.Type.EMAIL, "email_workflow", true);
        emailCh.setEvents("workflow.approve");
        var emailCh2 = makeChannel(NotificationChannelEntity.Type.EMAIL, "email_all", true);
        emailCh2.setEvents("");
        when(repo.findEnabledByTenantId(TENANT)).thenReturn(List.of(emailCh, emailCh2));
        var results = service.fire(TENANT, "workflow.approve", "u@e.com", Map.of("title", "Hi"));
        assertThat(results).hasSize(2);
    }

    /* === testSend === */

    @Test
    void testSend_channelNotFound_throwsIAE() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.testSend(id, "u@e.com", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel not found");
    }

    @Test
    void testSend_existingChannel_delegates() {
        UUID id = UUID.randomUUID();
        var ch = makeChannel(NotificationChannelEntity.Type.EMAIL, "test_ch", true);
        when(repo.findById(id)).thenReturn(java.util.Optional.of(ch));
        var r = service.testSend(id, "u@e.com", Map.of("title", "Hi"));
        assertThat(r.success()).isTrue();
        assertThat(r.detail()).isEqualTo("mocked");
    }

    @Test
    void testSend_noDispatcherForType_returnsError() {
        UUID id = UUID.randomUUID();
        var ch = makeChannel(NotificationChannelEntity.Type.DINGTALK, "no_disp", true);
        when(repo.findById(id)).thenReturn(java.util.Optional.of(ch));
        var r = service.testSend(id, "u@e.com", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("no dispatcher");
    }

    /* === helpers === */

    private NotificationChannelEntity makeChannel(
            NotificationChannelEntity.Type type, String name, boolean enabled) {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setId(UUID.randomUUID());
        ch.setTenantId(TENANT);
        ch.setType(type);
        ch.setName(name);
        ch.setConfig(new HashMap<>());
        ch.setEnabled(enabled);
        return ch;
    }
}