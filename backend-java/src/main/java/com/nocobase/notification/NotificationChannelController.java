package com.nocobase.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "通知渠道", description = "多渠道通知配置(Email / Webhook / 钉钉 / 企业微信)")
@RestController
@RequestMapping("/api/admin/notification-channels")
public class NotificationChannelController {

    private final NotificationChannelRepository repository;
    private final NotificationService service;

    public NotificationChannelController(NotificationChannelRepository repository,
                                          NotificationService service) {
        this.repository = repository;
        this.service = service;
    }

    @Operation(summary = "列出当前 tenant 所有通知 channel")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = repository.findByTenantId(user.tenantId())
                .stream().map(NotificationChannelController::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @Operation(summary = "创建新 channel")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @RequestBody @Valid ChannelRequest req) {
        NotificationChannelEntity e = new NotificationChannelEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(user.tenantId());
        applyRequest(e, req, user);
        repository.save(e);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", toDto(e)));
    }

    @Operation(summary = "更新 channel")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable UUID id,
                                                       @RequestBody @Valid ChannelRequest req) {
        NotificationChannelEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("not found: " + id));
        if (!e.getTenantId().equals(user.tenantId())) {
            return ResponseEntity.status(403).body(Map.of("code", 403, "message", "forbidden", "data", false));
        }
        applyRequest(e, req, user);
        e.setUpdatedAt(Instant.now());
        repository.save(e);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", toDto(e)));
    }

    @Operation(summary = "删除 channel")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable UUID id) {
        NotificationChannelEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("not found: " + id));
        if (!e.getTenantId().equals(user.tenantId())) {
            return ResponseEntity.status(403).body(Map.of("code", 403, "message", "forbidden", "data", false));
        }
        repository.delete(e);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", true));
    }

    @Operation(summary = "测试发送 — 用真实 channel 配置发一条 demo 消息")
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable UUID id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        NotificationChannelEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("not found: " + id));
        if (!e.getTenantId().equals(user.tenantId())) {
            return ResponseEntity.status(403).body(Map.of("code", 403, "message", "forbidden", "data", false));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", String.valueOf(body.getOrDefault("title", "[测试] NocoBase 通知")));
        payload.put("body", String.valueOf(body.getOrDefault("body",
                "这是一条测试通知,来自 NocoBase admin 后台")));
        String recipient = (String) body.getOrDefault("recipient", "");
        NotificationDispatcher.SendResult r = service.testSend(id, recipient, payload);
        return ResponseEntity.ok(Map.of(
                "code", r.success() ? 0 : 1,
                "message", r.success() ? "success" : "send failed",
                "data", Map.of("success", r.success(), "detail", r.detail())
        ));
    }

    @Operation(summary = "列出支持的 channel 类型及默认配置示例")
    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> types() {
        List<Map<String, Object>> types = List.of(
                Map.of(
                        "type", "EMAIL",
                        "label", "邮件",
                        "config_schema", Map.of(
                                "smtp_host", "smtp.example.com",
                                "smtp_port", 587,
                                "username", "noreply@example.com",
                                "password", "(应用专用密码)",
                                "from", "noreply@example.com",
                                "from_name", "NocoBase")),
                Map.of(
                        "type", "WEBHOOK",
                        "label", "通用 Webhook",
                        "config_schema", Map.of(
                                "url", "https://example.com/hook",
                                "method", "POST",
                                "headers", Map.of("X-Auth", "(optional)"),
                                "timeout_seconds", 5)),
                Map.of(
                        "type", "DINGTALK",
                        "label", "钉钉机器人",
                        "config_schema", Map.of(
                                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=...",
                                "secret", "(可选加签密钥)")),
                Map.of(
                        "type", "WECHAT_WORK",
                        "label", "企业微信机器人",
                        "config_schema", Map.of(
                                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."))
        );
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", types));
    }

    // ----- helpers -----

    private void applyRequest(NotificationChannelEntity e, ChannelRequest req, AuthenticatedUser user) {
        if (req.type != null) e.setType(NotificationChannelEntity.Type.valueOf(req.type.toUpperCase()));
        if (req.name != null) e.setName(req.name);
        if (req.config != null) e.setConfig(req.config);
        if (req.events != null) e.setEvents(req.events);
        if (req.enabled != null) e.setEnabled(req.enabled);
        if (req.description != null) e.setDescription(req.description);
        e.setUpdatedAt(Instant.now());
        if (e.getCreatedBy() == null) e.setCreatedBy(user.userId());
    }

    public static Map<String, Object> toDto(NotificationChannelEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId().toString());
        m.put("type", e.getType().name());
        m.put("name", e.getName());
        m.put("config", e.getConfig());
        m.put("events", e.getEvents());
        m.put("enabled", e.isEnabled());
        m.put("description", e.getDescription());
        m.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        m.put("updated_at", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        return m;
    }

    public record ChannelRequest(
            @JsonProperty("type") String type,
            @JsonProperty("name") String name,
            @JsonProperty("config") Map<String, Object> config,
            @JsonProperty("events") String events,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("description") String description
    ) {}
}
