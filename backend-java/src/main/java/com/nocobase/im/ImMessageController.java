package com.nocobase.im;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.dto.ImMessageDto;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.entity.ImMessageReactionEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 消息收发(列表、发送、编辑、删除、线程、搜索、已读、表情)。 */
@RestController
@Tag(name = "IM Messages", description = "即时消息 — 消息")
@RequestMapping("/api/im/messages")
public class ImMessageController {

    private final MessageService messageService;
    private final ReactionService reactionService;

    public ImMessageController(MessageService messageService, ReactionService reactionService) {
        this.messageService = messageService;
        this.reactionService = reactionService;
    }

    /** 游标分页拉取主消息。cursor 传上一页最后一条的 createdAt(ISO-8601)。 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam UUID channelId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Instant cursorAt = cursor == null || cursor.isBlank() ? null : Instant.parse(cursor);

        List<ImMessageEntity> msgs =
                messageService.list(channelId, cursorAt, safeLimit);
        String next = msgs.isEmpty()
                ? "" : msgs.get(msgs.size() - 1).getCreatedAt().toString();

        return Map.of("code", 0, "message", "success", "data", Map.of(
                "messages", msgs.stream().map(ImMessageDto::from).toList(),
                "limit", safeLimit,
                "next_cursor", next,
                "has_more", msgs.size() == safeLimit
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> send(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID channelId = UUID.fromString(String.valueOf(body.get("channelId")));
        UUID parentId = body.get("parentId") == null
                ? null : UUID.fromString(String.valueOf(body.get("parentId")));

        ImMessageEntity m = messageService.send(
                user.tenantId(), channelId, user.userId(),
                str(body.get("content")), str(body.get("contentType")), parentId);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", ImMessageDto.from(m)));
    }

    @PutMapping("/{id}")
    public Map<String, Object> edit(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ImMessageEntity m = messageService.edit(
                user.tenantId(), id, user.userId(), str(body.get("content")));
        return Map.of("code", 0, "message", "success", "data", ImMessageDto.from(m));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        messageService.delete(user.tenantId(), id, user.userId());
        return Map.of("code", 0, "message", "success",
                "data", Map.of("id", id.toString()));
    }

    /** 线程回复。 */
    @GetMapping("/{id}/thread")
    public Map<String, Object> thread(@PathVariable UUID id) {
        List<ImMessageDto> replies = messageService.thread(id).stream()
                .map(ImMessageDto::from).toList();
        return Map.of("code", 0, "message", "success", "data", Map.of("replies", replies));
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam UUID channelId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<ImMessageDto> hits = messageService.search(channelId, keyword, limit).stream()
                .map(ImMessageDto::from).toList();
        return Map.of("code", 0, "message", "success", "data", Map.of("messages", hits));
    }

    @GetMapping("/unread")
    public Map<String, Object> unread(
            @RequestParam UUID channelId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return Map.of("code", 0, "message", "success", "data", Map.of(
                "channelId", channelId.toString(),
                "unread_count", messageService.unreadCount(channelId, user.userId())
        ));
    }

    /** 标记已读到 lastMessageId(推进未读游标)。 */
    @PostMapping("/read")
    public Map<String, Object> markRead(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID channelId = UUID.fromString(String.valueOf(body.get("channelId")));
        UUID lastId = body.get("lastMessageId") == null
                ? null : UUID.fromString(String.valueOf(body.get("lastMessageId")));
        messageService.markRead(channelId, user.userId(), lastId);
        return Map.of("code", 0, "message", "success",
                "data", Map.of("channelId", channelId.toString()));
    }

    /** 添加表情回应(幂等)。 */
    @PostMapping("/{id}/reactions")
    public Map<String, Object> addReaction(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String emoji = str(body.get("emoji"));
        ImMessageReactionEntity r =
                reactionService.add(user.tenantId(), id, user.userId(), emoji);
        return Map.of("code", 0, "message", "success", "data", Map.of(
                "messageId", r.getMessageId().toString(),
                "emoji", r.getEmoji()
        ));
    }

    @DeleteMapping("/{id}/reactions")
    public Map<String, Object> removeReaction(
            @PathVariable UUID id,
            @RequestParam String emoji,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        reactionService.remove(id, user.userId(), emoji);
        return Map.of("code", 0, "message", "success",
                "data", Map.of("messageId", id.toString()));
    }

    @GetMapping("/{id}/reactions")
    public Map<String, Object> listReactions(@PathVariable UUID id) {
        List<Map<String, Object>> data = reactionService.list(id).stream()
                .map(r -> Map.<String, Object>of(
                        "userId", r.getUserId().toString(),
                        "emoji", r.getEmoji()))
                .toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
