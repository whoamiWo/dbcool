package com.nocobase.comment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.im.parser.RichTextParser;
import com.nocobase.meta.CollectionService;
import com.nocobase.notification.NotifyDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E3 接入点：评论 @提及 / 记录归属人通知。 */
class CommentNotificationListenerTest {

    private NotifyDispatcher dispatcher;
    private CollectionService collectionService;
    private CommentNotificationListener listener;

    private final UUID author = UUID.randomUUID();
    private final UUID mentioned = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = mock(NotifyDispatcher.class);
        collectionService = mock(CollectionService.class);
        listener = new CommentNotificationListener(dispatcher, collectionService);
    }

    private RecordCommentEntity comment() {
        RecordCommentEntity c = new RecordCommentEntity();
        c.setId(UUID.randomUUID());
        c.setTenantId("t1");
        c.setCollectionName("customer");
        c.setRecordId("r1");
        c.setAuthorId(author);
        c.setContent("@张三 看下");
        c.setCreatedAt(Instant.now());
        return c;
    }

    @Test
    @DisplayName("被 @ 的用户收到 MENTION（作者本人不通知）")
    void mentionedUser_receivesMention() {
        RecordCommentEntity c = comment();
        List<RichTextParser.Mention> mentions = List.of(
                new RichTextParser.Mention("张三", mentioned.toString(), 0),
                new RichTextParser.Mention("作者", author.toString(), 10));

        listener.onCommentMentioned(new CommentMentionedEvent(this, c, mentions));

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.MENTION), eq(mentioned), any());
        verify(dispatcher, never()).notifyOne(eq(NotifyDispatcher.MENTION), eq(author), any());
    }

    @Test
    @DisplayName("记录归属人收到 COMMENT（与作者相同则不通知）")
    void recordOwner_receivesComment() {
        RecordCommentEntity c = comment();
        when(collectionService.getRecord("customer", "r1", "t1"))
                .thenReturn(Map.of("created_by", owner.toString()));

        listener.onCommentMentioned(new CommentMentionedEvent(this, c, List.of()));

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.COMMENT), eq(owner), any());
    }

    @Test
    @DisplayName("作者评论自己的记录 → 不通知 COMMENT")
    void authorOwnsRecord_noCommentNotification() {
        RecordCommentEntity c = comment();
        when(collectionService.getRecord("customer", "r1", "t1"))
                .thenReturn(Map.of("created_by", author.toString()));

        listener.onCommentMentioned(new CommentMentionedEvent(this, c, List.of()));

        verify(dispatcher, never()).notifyOne(eq(NotifyDispatcher.COMMENT), any(), any());
    }

    @Test
    @DisplayName("@提及与归属人相同 → 只发一条（去重）")
    void mentionAndOwnerSame_deduped() {
        RecordCommentEntity c = comment();
        when(collectionService.getRecord("customer", "r1", "t1"))
                .thenReturn(Map.of("created_by", mentioned.toString()));

        listener.onCommentMentioned(new CommentMentionedEvent(this, c,
                List.of(new RichTextParser.Mention("张三", mentioned.toString(), 0))));

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.MENTION), eq(mentioned), any());
        verify(dispatcher, never()).notifyOne(eq(NotifyDispatcher.COMMENT), any(), any());
    }

    @Test
    @DisplayName("记录不存在 → 不抛异常")
    void recordMissing_noThrow() {
        RecordCommentEntity c = comment();
        when(collectionService.getRecord(any(), any(), any())).thenReturn(null);

        listener.onCommentMentioned(new CommentMentionedEvent(this, c, List.of()));
    }
}
