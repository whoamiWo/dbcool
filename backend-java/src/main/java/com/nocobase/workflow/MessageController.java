package com.nocobase.workflow;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信 API(US-506).
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;

    public MessageController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * 我的站内信(cursor 分页).
     * @param unreadOnly 只返回未读
     * @param limit 每页条数(默认 20,上限 100)
     * @param before cursor(ISO instant);返回 created_at < before 的 limit 条;为空则取最新
     */
    @GetMapping
    public Map<String, Object> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean unreadOnly,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String before
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

    @PostMapping("/{id}/read")
    @Transactional
    public Map<String, Object> markRead(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        MessageEntity m = messageRepository.findById(id).orElse(null);
        if (m == null || !m.getRecipient().equals(user.userId())) {
            return Map.of("code", 404, "message", "message 不存在");
        }
        m.setRead(true);
        messageRepository.save(m);
        return Map.of("code", 0, "message", "marked read");
    }

    private Map<String, Object> toDto(MessageEntity m) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", m.getId().toString());
        dto.put("type", m.getType());
        dto.put("title", m.getTitle());
        dto.put("body", m.getBody());
        dto.put("related_id", m.getRelatedId());
        dto.put("read", m.isRead());
        dto.put("created_at", m.getCreatedAt().toString());
        return dto;
    }
}
