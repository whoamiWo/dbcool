package com.nocobase.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.workflow.MessageEntity;
import com.nocobase.workflow.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

/**
 * E3 通知分发：站内必达、渠道偏好、免打扰、10 分钟聚合。
 */
class NotifyDispatcherTest {

    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private JdbcTemplate jdbc;
    private NotifyDispatcher dispatcher;

    private static final String TENANT = "tenant_a";
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        jdbc = mock(JdbcTemplate.class);
        dispatcher = new NotifyDispatcher(messageRepository, notificationService, jdbc);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("{}"));
        when(messageRepository.findByTenantIdAndRecipientAndDigestKeyAndCreatedAtAfterOrderByCreatedAtDesc(
                any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    private NotifyDispatcher.Payload payload(String digestKey) {
        return new NotifyDispatcher.Payload(TENANT, "待你审批", "有一条新的审批", "rel-1",
                "/approvals/rel-1", "HIGH", digestKey);
    }

    @Test
    @DisplayName("站内必达：写 messages 并调用外部渠道")
    void notifyOne_writesInAppAndFiresChannels() {
        dispatcher.notifyOne(NotifyDispatcher.APPROVAL_TODO, recipient, payload(null));

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        MessageEntity saved = captor.getValue();
        assertThat(saved.getRecipient()).isEqualTo(recipient);
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getType()).isEqualTo(NotifyDispatcher.APPROVAL_TODO);
        assertThat(saved.getDeeplink()).isEqualTo("/approvals/rel-1");
        assertThat(saved.getPriority()).isEqualTo("HIGH");
        verify(notificationService).fire(eq(TENANT), eq("notify.APPROVAL_TODO"),
                eq(recipient.toString()), any());
    }

    @Test
    @DisplayName("聚合：10 分钟内同 digest_key → 累加计数且不重复投递")
    void digest_mergesWithinWindow() {
        MessageEntity existing = new MessageEntity();
        existing.setId(UUID.randomUUID());
        existing.setRecipient(recipient);
        existing.setTenantId(TENANT);
        existing.setType(NotifyDispatcher.APPROVAL_TODO);
        existing.setDigestCount(2);
        existing.setCreatedAt(Instant.now().minusSeconds(60));
        when(messageRepository.findByTenantIdAndRecipientAndDigestKeyAndCreatedAtAfterOrderByCreatedAtDesc(
                any(), any(), any(), any(), any())).thenReturn(List.of(existing));

        dispatcher.notifyOne(NotifyDispatcher.APPROVAL_TODO, recipient, payload("approval-todo:t1"));

        assertThat(existing.getDigestCount()).isEqualTo(3);
        verify(notificationService, never()).fire(any(), any(), any(), any());
    }

    @Test
    @DisplayName("免打扰：quietHours 内不投递外部渠道，但站内仍留档")
    void quietHours_skipsChannelsKeepsInApp() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("{\"notify\":{\"quietHours\":[\"00:00\",\"23:59\"]}}"));

        MessageEntity msg = dispatcher.notifyOne(NotifyDispatcher.APPROVAL_TODO, recipient, payload(null));

        assertThat(msg.getId()).isNotNull();
        assertThat(msg.getChannelSentJson()).contains("quiet_hours");
        verify(notificationService, never()).fire(any(), any(), any(), any());
    }

    @Test
    @DisplayName("偏好关闭该类型渠道 → 不投递外部渠道")
    void preferenceOff_skipsChannels() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("{\"notify\":{\"APPROVAL_TODO\":[]}}"));

        MessageEntity msg = dispatcher.notifyOne(NotifyDispatcher.APPROVAL_TODO, recipient, payload(null));

        assertThat(msg.getChannelSentJson()).contains("preference_off");
        verify(notificationService, never()).fire(any(), any(), any(), any());
    }

    @Test
    @DisplayName("外部渠道异常 → 站内消息不受影响（隔离故障）")
    void channelFailure_doesNotBreakInApp() {
        when(notificationService.fire(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("smtp down"));

        MessageEntity msg = dispatcher.notifyOne(NotifyDispatcher.APPROVAL_TODO, recipient, payload(null));

        assertThat(msg.getChannelSentJson()).contains("smtp down");
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(msg);
    }

    @Test
    @DisplayName("批量通知去重收件人")
    void notify_dedupesRecipients() {
        UUID other = UUID.randomUUID();
        dispatcher.notify(NotifyDispatcher.APPROVAL_TODO, List.of(recipient, recipient, other),
                payload(null));

        verify(messageRepository, org.mockito.Mockito.times(2)).save(any(MessageEntity.class));
    }
}
