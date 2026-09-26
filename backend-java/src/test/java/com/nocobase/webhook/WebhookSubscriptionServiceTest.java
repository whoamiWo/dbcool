package com.nocobase.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.config.AsyncTask;
import com.nocobase.config.AsyncTaskPublishException;
import com.nocobase.config.AsyncTaskPublisher;
import com.nocobase.event.RecordChangeEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WebhookSubscriptionService 单元测试(PHASE 55 Stage 2 — MQ 改造后)。
 *
 * <p>改造后:dispatch 将任务发布到 MQ,不再同步 HTTP POST。
 * 测试 mock AsyncTaskPublisher 而非 RestTemplate。
 */
class WebhookSubscriptionServiceTest {

    private WebhookSubscriptionRepository repository;
    private AsyncTaskPublisher publisher;
    private WebhookSubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookSubscriptionRepository.class);
        publisher = mock(AsyncTaskPublisher.class);
        service = new WebhookSubscriptionService(repository, publisher);
    }

    @Test
    void dispatch_noMatchingSubscriptions_doesNothing() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        assertDoesNotThrow(() -> service.dispatch(event(RecordChangeEvent.ChangeType.CREATE)));
    }

    @Test
    void dispatch_createEvent_publishesToMq() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", null)));
        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));
        verify(publisher).publish(any(AsyncTask.class));
    }

    @Test
    void dispatch_withSecret_includesSecretInPayload() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", "s3cret")));

        org.mockito.ArgumentCaptor<AsyncTask> captor =
                org.mockito.ArgumentCaptor.forClass(AsyncTask.class);
        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));
        verify(publisher).publish(captor.capture());

        Map<String, Object> payload = captor.getValue().getPayload();
        assert "s3cret".equals(payload.get("secret"));
        assert "https://example.com/hook".equals(payload.get("targetUrl"));
        assert "on_create".equals(payload.get("event"));
        assert "orders".equals(payload.get("collection"));
    }

    @Test
    void dispatch_withoutSecret_noSecretInPayload() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", null)));

        org.mockito.ArgumentCaptor<AsyncTask> captor =
                org.mockito.ArgumentCaptor.forClass(AsyncTask.class);
        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));
        verify(publisher).publish(captor.capture());

        Map<String, Object> payload = captor.getValue().getPayload();
        assert payload.get("secret") == null || "".equals(payload.get("secret"));
    }

    @Test
    void dispatch_mqUnavailable_throwsException() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", null)));
        doThrow(new AsyncTaskPublishException("MQ 不可达", null))
                .when(publisher).publish(any(AsyncTask.class));

        assertThrows(AsyncTaskPublishException.class,
                () -> service.dispatch(event(RecordChangeEvent.ChangeType.CREATE)));
    }

    private RecordChangeEvent event(RecordChangeEvent.ChangeType type) {
        return new RecordChangeEvent(type, "orders", "r-1",
                Map.of("amount", 100), "tenant_default", UUID.randomUUID());
    }

    private WebhookSubscriptionEntity sub(String url, String secret) {
        WebhookSubscriptionEntity e = new WebhookSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("tenant_default");
        e.setCollectionName("orders");
        e.setEvent("on_create");
        e.setTargetUrl(url);
        e.setSecret(secret);
        e.setEnabled(true);
        return e;
    }
}