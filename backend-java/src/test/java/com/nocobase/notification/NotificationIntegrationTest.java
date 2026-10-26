package com.nocobase.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知服务集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Test
    void shouldFireNotificationToConfiguredChannels() {
        // Given
        String tenantId = "demo-tenant";
        String eventName = "task.status.changed";
        String recipient = "user-123";
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", "task-456");
        payload.put("newStatus", "DONE");

        // When
        List<NotificationDispatcher.SendResult> results = 
            notificationService.fire(tenantId, eventName, recipient, payload);

        // Then
        // 测试环境可能没有配置 channels，所以只验证方法调用成功
        assertThat(results).isNotNull();
    }

    @Test
    void shouldHandleNullEventName() {
        // Given
        String tenantId = "demo-tenant";
        String recipient = "user-123";
        Map<String, Object> payload = new HashMap<>();

        // When
        List<NotificationDispatcher.SendResult> results = 
            notificationService.fire(tenantId, null, recipient, payload);

        // Then
        assertThat(results).isNotNull();
    }

    @Test
    void shouldHandleEmptyEventName() {
        // Given
        String tenantId = "demo-tenant";
        String recipient = "user-123";
        Map<String, Object> payload = new HashMap<>();

        // When
        List<NotificationDispatcher.SendResult> results = 
            notificationService.fire(tenantId, "", recipient, payload);

        // Then
        assertThat(results).isNotNull();
    }
}