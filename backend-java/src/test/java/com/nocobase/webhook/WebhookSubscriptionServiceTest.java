package com.nocobase.webhook;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nocobase.event.RecordChangeEvent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WebhookSubscriptionService 单元测试(Week 41 复核 D5.3)。
 */
class WebhookSubscriptionServiceTest {

    private WebhookSubscriptionRepository repository;
    private WebhookSubscriptionService service;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(WebhookSubscriptionRepository.class);
        service = new WebhookSubscriptionService(repository);
        // restTemplate 是 final 字段,反射替换
        restTemplate = mock(RestTemplate.class);
        Field f = WebhookSubscriptionService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(service, restTemplate);
    }

    @Test
    void dispatch_noMatchingSubscriptions_doesNothing() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString())).thenReturn(List.of());

        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));

        verifyNoInteractions(restTemplate);
    }

    @Test
    void dispatch_createEvent_mapsToOnCreate() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                eq("tenant_default"), eq("orders"), eq("on_create")))
                .thenReturn(List.of(sub("https://example.com/hook", null)));

        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    void dispatch_updateEvent_mapsToOnUpdate() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                eq("tenant_default"), eq("orders"), eq("on_update")))
                .thenReturn(List.of(sub("https://example.com/hook", null)));

        service.dispatch(event(RecordChangeEvent.ChangeType.UPDATE));

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    void dispatch_withSecret_addsSignatureHeader() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", "s3cret")));

        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        assertNotNull(captor.getValue().getHeaders().getFirst("X-Webhook-Signature"));
    }

    @Test
    void dispatch_withoutSecret_noSignatureHeader() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", null)));

        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        assertNull(captor.getValue().getHeaders().getFirst("X-Webhook-Signature"));
    }

    @Test
    void dispatch_sendFailure_doesNotPropagate() {
        when(repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(sub("https://example.com/hook", null)));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("boom"));

        // Webhook 是尽力而为,失败不应冒泡
        service.dispatch(event(RecordChangeEvent.ChangeType.CREATE));
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
