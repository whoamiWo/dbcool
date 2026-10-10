package com.nocobase.im;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 频道管理(列表、创建、直聊、成员)。 */
@RestController
@Tag(name = "IM Channels", description = "即时消息 — 频道")
@RequestMapping("/api/im/channels")
public class ImChannelController {

    private final ChannelService channelService;
    private final PresenceService presenceService;
    private final AuditService auditService;

    public ImChannelController(ChannelService channelService, PresenceService presenceService, AuditService auditService) {
        this.channelService = channelService;
        this.presenceService = presenceService;
        this.auditService = auditService;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = channelService
                .listMine(user.tenantId(), user.userId())
                .stream().map(this::toDto).toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String type = str(body.get("type"));
        List<UUID> memberIds = parseUuids(body.get("memberIds"));

        ImChannelEntity c = channelService.create(
                user.tenantId(), user.userId(),
                str(body.get("name")), type, str(body.get("topic")), memberIds);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", toDto(c)));
    }

    /** 获取或创建与某人的一对一会话(幂等)。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/direct/{userId}")
    public Map<String, Object> direct(
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ImChannelEntity c = channelService.getOrCreateDirect(user.tenantId(), user.userId(), userId);
        return Map.of("code", 0, "message", "success", "data", toDto(c));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return Map.of("code", 0, "message", "success",
                "data", toDto(channelService.mustGet(user.tenantId(), id)));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/members")
    public Map<String, Object> members(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<Map<String, Object>> data = channelService.members(id).stream()
                .map(m -> Map.<String, Object>of(
                        "userId", m.getUserId().toString(),
                        "role", m.getRole(),
                        "muted", m.isMuted(),
                        "online", presenceService.isOnline(user.tenantId(), m.getUserId())))
                .toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/members")
    public Map<String, Object> join(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID target = body == null || body.get("userId") == null
                ? user.userId()
                : UUID.fromString(String.valueOf(body.get("userId")));
        String role = body == null ? null : str(body.get("role"));
        channelService.join(user.tenantId(), id, target, role);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("channel_id", id.toString());
        if (role != null) payload.put("role", role);
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "im.channel.member.add", "im_channel_member", target.toString(), payload);
        return Map.of("code", 0, "message", "success",
                "data", Map.of("channelId", id.toString(), "userId", target.toString()));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}/members/me")
    public Map<String, Object> leave(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        channelService.leave(id, user.userId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "im.channel.member.remove", "im_channel_member", user.userId().toString(),
                Map.of("channel_id", id.toString()));
        return Map.of("code", 0, "message", "success",
                "data", Map.of("channelId", id.toString()));
    }

    /** 在线状态心跳。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/presence/heartbeat")
    public Map<String, Object> heartbeat(@AuthenticationPrincipal AuthenticatedUser user) {
        presenceService.heartbeat(user.tenantId(), user.userId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "im.presence.heartbeat", "im_presence", user.userId().toString(), Map.of());
        return Map.of("code", 0, "message", "success", "data", Map.of());
    }

    private Map<String, Object> toDto(ImChannelEntity c) {
        return Map.of(
                "id", c.getId().toString(),
                "name", c.getName() == null ? "" : c.getName(),
                "type", c.getType(),
                "topic", c.getTopic() == null ? "" : c.getTopic(),
                "createdBy", c.getCreatedBy().toString(),
                "createdAt", c.getCreatedAt().toString(),
                "updatedAt", c.getUpdatedAt().toString(),
                "archived", c.getArchivedAt() != null
        );
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> parseUuids(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(o -> o != null)
                .map(o -> UUID.fromString(String.valueOf(o)))
                .toList();
    }
}
