package com.nocobase.im;

import com.nocobase.im.entity.ImHuddleEntity;
import com.nocobase.im.entity.ImHuddleParticipantEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Huddle 语音会话 REST API。
 */
@RestController
@Tag(name = "IM Huddle", description = "语音会话管理")
@RequestMapping("/api/im/huddle")
public class ImHuddleController {

    private final HuddleService huddleService;

    public ImHuddleController(HuddleService huddleService) {
        this.huddleService = huddleService;
    }

    /** 创建语音会话 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        UUID channelId = UUID.fromString(String.valueOf(body.get("channelId")));
        String name = (String) body.get("name");
        
        ImHuddleEntity huddle = huddleService.create(user.tenantId(), user.userId(), channelId, name);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "id", huddle.getId().toString(),
                        "channelId", huddle.getChannelId().toString(),
                        "name", huddle.getName(),
                        "status", huddle.getStatus(),
                        "createdAt", huddle.getCreatedAt().toString()
                )
        ));
    }

    /** 加入语音会话 */
    @PostMapping("/{huddleId}/join")
    public ResponseEntity<Map<String, Object>> join(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImHuddleParticipantEntity participant = huddleService.join(huddleId, user.userId(), user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "huddleId", huddleId.toString(),
                        "userId", participant.getUserId().toString(),
                        "joinedAt", participant.getJoinedAt().toString()
                )
        ));
    }

    /** 离开语音会话 */
    @PostMapping("/{huddleId}/leave")
    public ResponseEntity<Map<String, Object>> leave(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        huddleService.leave(huddleId, user.userId(), user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("huddleId", huddleId.toString())
        ));
    }

    /** 结束语音会话 */
    @PostMapping("/{huddleId}/end")
    public ResponseEntity<Map<String, Object>> end(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImHuddleEntity huddle = huddleService.end(huddleId, user.userId(), user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "huddleId", huddleId.toString(),
                        "status", huddle.getStatus(),
                        "endedAt", huddle.getEndedAt().toString()
                )
        ));
    }

    /** 切换静音 */
    @PostMapping("/{huddleId}/mute")
    public ResponseEntity<Map<String, Object>> toggleMute(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImHuddleParticipantEntity participant = huddleService.toggleMute(huddleId, user.userId(), user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "huddleId", huddleId.toString(),
                        "muted", participant.getIsMuted()
                )
        ));
    }

    /** 切换屏幕共享 */
    @PostMapping("/{huddleId}/screen-share")
    public ResponseEntity<Map<String, Object>> toggleScreenShare(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImHuddleParticipantEntity participant = huddleService.toggleScreenShare(huddleId, user.userId(), user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "huddleId", huddleId.toString(),
                        "screenSharing", participant.getIsScreenSharing()
                )
        ));
    }

    /** 获取活跃会话列表 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listActive(
            @RequestParam UUID channelId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<ImHuddleEntity> huddles = huddleService.listActive(user.tenantId(), channelId);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("huddles", huddles)
        ));
    }

    /** 获取会话详情 */
    @GetMapping("/{huddleId}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImHuddleEntity huddle = huddleService.get(huddleId, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", huddle
        ));
    }

    /** 获取会话参与者列表 */
    @GetMapping("/{huddleId}/participants")
    public ResponseEntity<Map<String, Object>> listParticipants(
            @PathVariable UUID huddleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<ImHuddleParticipantEntity> participants = huddleService.listParticipants(huddleId);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("participants", participants)
        ));
    }
}
