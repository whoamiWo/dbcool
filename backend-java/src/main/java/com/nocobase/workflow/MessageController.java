package com.nocobase.workflow;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 站内信 API(US-506).
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Messages", description = "站内信")
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;
    private final AuditService auditService;

    public MessageController(MessageRepository messageRepository, AuditService auditService) {
        this.messageRepository = messageRepository;
        this.auditService = auditService;
    }

    /**
     * 我的站内信(cursor 分页).
     * @param unreadOnly 只返回未读
     * @param limit 每页条数(默认 20,上限 100)
     * @param before cursor(ISO instant);返回 created_at < before 的 limit 条;为空则取最新
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean unreadOnly,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String before,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String type
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<MessageEntity> msgs;
        if (before != null && !before.isBlank()) {
            try {
                Instant cursor = Instant.parse(before);
                msgs = unreadOnly
                        ? messageRepository.findByRecipientAndCreatedAtLessThanAndReadOrderByCreatedAtDesc(
                                user.userId(), cursor, false, PageRequest.of(0, safeLimit))
                        : messageRepository.findByRecipientAndCreatedAtLessThanOrderByCreatedAtDesc(
                                user.userId(), cursor, PageRequest.of(0, safeLimit));
            } catch (Exception e) {
                return Map.of("code", 400, "message", "before 必须是 ISO-8601 instant");
            }
        } else if (type != null && !type.isBlank()) {
            msgs = messageRepository.findByRecipientAndTypeOrderByCreatedAtDesc(
                    user.userId(), type.toUpperCase());
            if (unreadOnly) {
                msgs = msgs.stream().filter(m -> !m.isRead()).toList();
            }
            if (msgs.size() > safeLimit) msgs = msgs.subList(0, safeLimit);
        } else {
            msgs = unreadOnly
                    ? messageRepository.findByRecipientAndReadOrderByCreatedAtDesc(user.userId(), false)
                    : messageRepository.findByRecipientOrderByCreatedAtDesc(user.userId());
            // 应用 limit
            if (msgs.size() > safeLimit) msgs = msgs.subList(0, safeLimit);
        }
        long unread = messageRepository.countByRecipientAndRead(user.userId(), false);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("message", "success");
        // 计算 next_cursor(最后一条的 created_at)
        String nextCursor = msgs.isEmpty() ? null
                : msgs.get(msgs.size() - 1).getCreatedAt().toString();
        resp.put("data", Map.of(
                "unread_count", unread,
                "messages", msgs.stream().map(this::toDto).toList(),
                "limit", safeLimit,
                "next_cursor", nextCursor == null ? "" : nextCursor,
                "has_more", msgs.size() == safeLimit
        ));
        return resp;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/read")
    @Transactional
    public Map<String, Object> markRead(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        MessageEntity m = messageRepository.findById(id).orElse(null);
        if (m == null || !m.getRecipient().equals(user.userId())) {
            return Map.of("code", 404, "message", "message 不存在");
        }
        m.setRead(true);
        m.setReadAt(Instant.now());
        messageRepository.save(m);
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "message.mark_read", "message", id.toString(),
                Map.of("messageId", id.toString()));
        return Map.of("code", 0, "message", "marked read");
    }

    /** 未读数（顶栏铃铛轮询 30s）。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(@AuthenticationPrincipal AuthenticatedUser user) {
        long count = messageRepository.countByRecipientAndTenantIdAndRead(
                user.userId(), user.tenantId(), false);
        return Map.of("code", 0, "message", "success", "data", Map.of("unread", count));
    }

    /** 全部已读。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/read-all")
    @Transactional
    public Map<String, Object> readAll(@AuthenticationPrincipal AuthenticatedUser user) {
        List<MessageEntity> unread = messageRepository.findByRecipientAndReadFalse(user.userId());
        Instant now = Instant.now();
        int marked = 0;
        for (MessageEntity m : unread) {
            // 只标记当前租户（跨租户脏数据不改写、也不计入返回数量）
            if (!user.tenantId().equals(m.getTenantId())) continue;
            m.setRead(true);
            m.setReadAt(now);
            marked++;
        }
        messageRepository.saveAll(unread);
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "message.read_all", "message", null,
                Map.of("marked", marked));
        return Map.of("code", 0, "message", "success",
                "data", Map.of("marked", marked));
    }

    private Map<String, Object> toDto(MessageEntity m) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", m.getId().toString());
        dto.put("type", m.getType());
        dto.put("title", m.getTitle());
        dto.put("body", m.getBody());
        dto.put("related_id", m.getRelatedId());
        dto.put("deeplink", m.getDeeplink());
        dto.put("priority", m.getPriority());
        dto.put("digest_count", m.getDigestCount());
        dto.put("read", m.isRead());
        dto.put("read_at", m.getReadAt() == null ? null : m.getReadAt().toString());
        dto.put("created_at", m.getCreatedAt().toString());
        return dto;
    }
}
