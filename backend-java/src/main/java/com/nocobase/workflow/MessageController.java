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

    @GetMapping
    public Map<String, Object> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        List<MessageEntity> msgs = unreadOnly
                ? messageRepository.findByRecipientAndReadOrderByCreatedAtDesc(user.userId(), false)
                : messageRepository.findByRecipientOrderByCreatedAtDesc(user.userId());
        long unread = messageRepository.countByRecipientAndRead(user.userId(), false);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 0);
        resp.put("message", "success");
        resp.put("data", Map.of(
                "unread_count", unread,
                "messages", msgs.stream().map(this::toDto).toList()
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
