package com.nocobase.comment;

import com.nocobase.meta.CollectionService;
import com.nocobase.notification.NotifyDispatcher;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评论通知（E3 接入点）：@提及 → MENTION；记录归属人 → COMMENT。
 *
 * <p>此前 {@code RecordCommentService} 只发布 {@link CommentMentionedEvent}，
 * 全仓没有监听方 —— 事件发了等于没发（"已接线"错觉）。
 */
@Component
public class CommentNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(CommentNotificationListener.class);

    private final NotifyDispatcher notifyDispatcher;
    private final CollectionService collectionService;

    public CommentNotificationListener(NotifyDispatcher notifyDispatcher,
                                       CollectionService collectionService) {
        this.notifyDispatcher = notifyDispatcher;
        this.collectionService = collectionService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentMentioned(CommentMentionedEvent event) {
        RecordCommentEntity comment = event.getComment();
        if (comment == null) return;
        String deeplink = "/records/" + comment.getCollectionName() + "/" + comment.getRecordId();
        String title = "你被提及了";
        String body = "在 " + comment.getCollectionName() + " 的评论中提到了你";

        Set<UUID> notified = new HashSet<>();
        for (var mention : event.getMentions()) {
            UUID userId = parseUuid(mention.userId());
            if (userId == null || userId.equals(comment.getAuthorId())) continue;
            if (!notified.add(userId)) continue;
            try {
                notifyDispatcher.notifyOne(NotifyDispatcher.MENTION, userId,
                        new NotifyDispatcher.Payload(comment.getTenantId(), title, body,
                                comment.getRecordId(), deeplink, "NORMAL",
                                "mention:" + comment.getId() + ":" + userId));
            } catch (Exception e) {
                log.warn("[comment] @提及通知失败 user={}: {}", userId, e.getMessage());
            }
        }

        // 记录归属人收到普通评论通知（自己评论自己的记录不通知）
        UUID owner = resolveRecordOwner(comment);
        if (owner != null && !owner.equals(comment.getAuthorId()) && notified.add(owner)) {
            try {
                notifyDispatcher.notifyOne(NotifyDispatcher.COMMENT, owner,
                        new NotifyDispatcher.Payload(comment.getTenantId(), "有新评论",
                                "你的记录收到一条新评论", comment.getRecordId(), deeplink,
                                "NORMAL", "comment:" + comment.getId() + ":" + owner));
            } catch (Exception e) {
                log.warn("[comment] 评论通知失败 owner={}: {}", owner, e.getMessage());
            }
        }
    }

    private UUID resolveRecordOwner(RecordCommentEntity comment) {
        try {
            Map<String, Object> record = collectionService.getRecord(
                    comment.getCollectionName(), comment.getRecordId(), comment.getTenantId());
            if (record == null) return null;
            Object createdBy = record.get("created_by");
            return createdBy == null ? null : parseUuid(createdBy.toString());
        } catch (Exception e) {
            log.debug("[comment] 记录归属人解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
