package com.nocobase.im;

import com.nocobase.audit.AuditService;
import com.nocobase.im.entity.ImMessageReactionEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 表情回应。同一用户对同一消息的同一 emoji 幂等(唯一约束 + 查询去重)。 */
@Service
public class ReactionService {

    private final ImReactionRepository reactionRepository;
    private final AuditService auditService;

    public ReactionService(ImReactionRepository reactionRepository, AuditService auditService) {
        this.reactionRepository = reactionRepository;
        this.auditService = auditService;
    }

    /** 添加回应;已存在则原样返回(幂等)。 */
    @Transactional
    public ImMessageReactionEntity add(String tenantId, UUID messageId, UUID userId, String emoji) {
        validateEmoji(emoji);
        return reactionRepository
                .findByMessageIdAndUserIdAndEmoji(messageId, userId, emoji)
                .orElseGet(() -> {
                    ImMessageReactionEntity r = new ImMessageReactionEntity();
                    r.setId(UUID.randomUUID());
                    r.setMessageId(messageId);
                    r.setUserId(userId);
                    r.setEmoji(emoji);
                    r.setTenantId(tenantId);
                    ImMessageReactionEntity saved = reactionRepository.save(r);
                    auditService.log(tenantId, userId.toString(), null,
                            "im.reaction.add", "im_message_reaction", saved.getId().toString(),
                            Map.of("message_id", messageId.toString(), "emoji", emoji));
                    return saved;
                });
    }

    /** 取消回应(只能取消自己的)。 */
    @Transactional
    public void remove(UUID messageId, UUID userId, String emoji, String tenantId) {
        validateEmoji(emoji);
        ImMessageReactionEntity r = reactionRepository
                .findByMessageIdAndUserIdAndEmoji(messageId, userId, emoji)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "回应不存在"));
        if (!tenantId.equals(r.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
        auditService.log(tenantId, userId.toString(), null,
                "im.reaction.remove", "im_message_reaction", r.getId().toString(),
                Map.of("message_id", messageId.toString(), "emoji", emoji));
        reactionRepository.delete(r);
    }

    public List<ImMessageReactionEntity> list(UUID messageId) {
        return reactionRepository.findByMessageId(messageId);
    }

    private static void validateEmoji(String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emoji 非法");
        }
    }
}
