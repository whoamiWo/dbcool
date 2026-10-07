package com.nocobase.webhook;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook 订阅管理 API(Week 41 复核 D5.3)。
 *
 * <p>让管理员配置"哪个 collection 发生什么事件时,推送到哪个外部 URL"。
 */
@RestController
@Tag(name = "Webhooks", description = "Webhook 出口订阅(Week 41 复核 D5.3)")
@RequestMapping("/api/admin/webhooks")
public class WebhookSubscriptionController {

    private static final Set<String> ALLOWED_EVENTS =
            Set.of("on_create", "on_update", "on_delete");

    private final WebhookSubscriptionRepository repository;
    private final AuditService auditService;

    public WebhookSubscriptionController(WebhookSubscriptionRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> list() {
        String tenant = TenantContext.currentTenantId();
        List<Map<String, Object>> data = repository.findByTenantIdOrderByCreatedAtDesc(tenant)
                .stream().map(this::toDto).toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String tenant = TenantContext.currentTenantId();
        String collection = str(body.get("collectionName"));
        String event = str(body.get("event"));
        String url = str(body.get("targetUrl"));

        if (collection == null || collection.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "collectionName 必填");
        }
        if (event == null || !ALLOWED_EVENTS.contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "event 必须是 on_create / on_update / on_delete");
        }
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetUrl 必须是 http(s) 地址");
        }

        WebhookSubscriptionEntity e = new WebhookSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenant);
        e.setCollectionName(collection);
        e.setEvent(event);
        e.setTargetUrl(url);
        e.setSecret(str(body.get("secret")));
        e.setEnabled(body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        e.setCreatedAt(Instant.now());

        WebhookSubscriptionEntity saved = repository.save(e);
        auditService.log(TenantContext.currentTenantId(), null, "system",
                "webhook.create", "webhook_subscription", saved.getId().toString(),
                Map.of("collection", collection, "event", event, "url", url));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", toDto(saved)));
    }

    /** 启停订阅(避免删除后重建)。 */
    @PutMapping("/{id}/enabled")
    public Map<String, Object> setEnabled(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String tenant = TenantContext.currentTenantId();
        WebhookSubscriptionEntity e = mustGet(id);
        boolean oldEnabled = e.isEnabled();
        e.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        WebhookSubscriptionEntity saved = repository.save(e);
        auditService.log(tenant, null, "system",
                "webhook.update", "webhook_subscription", id.toString(),
                Map.of("before_enabled", oldEnabled, "after_enabled", e.isEnabled()));
        return Map.of("code", 0, "message", "success", "data", toDto(saved));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        String tenant = TenantContext.currentTenantId();
        WebhookSubscriptionEntity e = mustGet(id);
        repository.delete(e);
        auditService.log(tenant, null, "system",
                "webhook.delete", "webhook_subscription", id.toString(),
                Map.of("collection", e.getCollectionName(), "event", e.getEvent()));
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id.toString()));
    }

    private WebhookSubscriptionEntity mustGet(UUID id) {
        String tenant = TenantContext.currentTenantId();
        return repository.findById(id)
                .filter(e -> tenant.equals(e.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订阅不存在"));
    }

    private Map<String, Object> toDto(WebhookSubscriptionEntity e) {
        return Map.of(
                "id", e.getId().toString(),
                "collectionName", e.getCollectionName(),
                "event", e.getEvent(),
                "targetUrl", e.getTargetUrl(),
                "hasSecret", e.getSecret() != null && !e.getSecret().isBlank(),
                "enabled", e.isEnabled(),
                "createdAt", e.getCreatedAt().toString()
        );
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
