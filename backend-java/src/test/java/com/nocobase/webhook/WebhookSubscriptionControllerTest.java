package com.nocobase.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * WebhookSubscriptionController 单元测试(Week 41 复核 D5.3)。
 */
class WebhookSubscriptionControllerTest {

    private WebhookSubscriptionRepository repository;
    private WebhookSubscriptionController controller;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookSubscriptionRepository.class);
        controller = new WebhookSubscriptionController(repository);
        TenantContext.set("tenant_default");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void list_returnsSubscriptions() {
        when(repository.findByTenantIdOrderByCreatedAtDesc("tenant_default"))
                .thenReturn(List.of(sub("on_create")));

        Map<String, Object> resp = controller.list();

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat((List<?>) resp.get("data")).hasSize(1);
    }

    @Test
    void create_valid_returnsCreated() {
        when(repository.save(any(WebhookSubscriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var resp = controller.create(Map.of(
                "collectionName", "orders",
                "event", "on_create",
                "targetUrl", "https://example.com/hook"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void create_invalidEvent_returns400() {
        assertThatThrownBy(() -> controller.create(Map.of(
                "collectionName", "orders",
                "event", "on_publish",
                "targetUrl", "https://example.com/hook")))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_missingCollection_returns400() {
        assertThatThrownBy(() -> controller.create(Map.of(
                "event", "on_create",
                "targetUrl", "https://example.com/hook")))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_nonHttpUrl_returns400() {
        assertThatThrownBy(() -> controller.create(Map.of(
                "collectionName", "orders",
                "event", "on_create",
                "targetUrl", "ftp://example.com/hook")))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void setEnabled_togglesFlag() {
        WebhookSubscriptionEntity e = sub("on_create");
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.setEnabled(e.getId(), Map.of("enabled", false));

        assertThat(e.isEnabled()).isFalse();
        verify(repository).save(e);
    }

    @Test
    void delete_existing_returnsOk() {
        WebhookSubscriptionEntity e = sub("on_create");
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        Map<String, Object> resp = controller.delete(e.getId());

        assertThat(resp.get("code")).isEqualTo(0);
        verify(repository).delete(e);
    }

    @Test
    void delete_crossTenant_returns404() {
        WebhookSubscriptionEntity e = sub("on_create");
        e.setTenantId("tenant_other");
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> controller.delete(e.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(x -> ((ResponseStatusException) x).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_missing_returns404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.delete(id))
                .isInstanceOf(ResponseStatusException.class)
                .matches(x -> ((ResponseStatusException) x).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    private WebhookSubscriptionEntity sub(String event) {
        WebhookSubscriptionEntity e = new WebhookSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("tenant_default");
        e.setCollectionName("orders");
        e.setEvent(event);
        e.setTargetUrl("https://example.com/hook");
        e.setSecret("s3cret");
        e.setEnabled(true);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
