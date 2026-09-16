package com.nocobase.im;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public ImChannelController(ChannelService channelService, PresenceService presenceService) {
        this.channelService = channelService;
        this.presenceService = presenceService;
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = channelService
                .listMine(user.tenantId(), user.userId())
                .stream().map(this::toDto).toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

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
    @PostMapping("/direct/{userId}")
    public Map<String, Object> direct(
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ImChannelEntity c = channelService.getOrCreateDirect(user.tenantId(), user.userId(), userId);
        return Map.of("code", 0, "message", "success", "data", toDto(c));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return Map.of("code", 0, "message", "success",
                "data", toDto(channelService.mustGet(user.tenantId(), id)));
    }

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

    @PostMapping("/{id}/members")
    public Map<String, Object> join(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID target = body == null || body.get("userId") == null
                ? user.userId()
                : UUID.fromString(String.valueOf(body.get("userId")));
        channelService.join(user.tenantId(), id, target,
                body == null ? null : str(body.get("role")));
        return Map.of("code", 0, "message", "success",
                "data", Map.of("channelId", id.toString(), "userId", target.toString()));
    }

    @DeleteMapping("/{id}/members/me")
    public Map<String, Object> leave(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        channelService.leave(id, user.userId());
        return Map.of("code", 0, "message", "success",
                "data", Map.of("channelId", id.toString()));
    }

    /** 在线状态心跳。 */
    @PostMapping("/presence/heartbeat")
    public Map<String, Object> heartbeat(@AuthenticationPrincipal AuthenticatedUser user) {
        presenceService.heartbeat(user.tenantId(), user.userId());
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
