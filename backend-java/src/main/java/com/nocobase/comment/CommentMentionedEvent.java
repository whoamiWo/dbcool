package com.nocobase.comment;

import com.nocobase.im.parser.RichTextParser;

import java.util.List;

/**
 * 评论中发生 @提及时发布的事件，供通知模块消费。
 *
 * <p>携带原始实体而非 ID，避免监听方二次查库；
 * 未提交事务中的事件应在 AFTER_COMMIT 阶段消费（监听方自行决定）。
 */
public class CommentMentionedEvent {

    private final RecordCommentEntity comment;
    private final List<RichTextParser.Mention> mentions;

    public CommentMentionedEvent(Object source,
                                  RecordCommentEntity comment,
                                  List<RichTextParser.Mention> mentions) {
        this.comment = comment;
        this.mentions = mentions;
    }

    public RecordCommentEntity getComment() { return comment; }

    public List<RichTextParser.Mention> getMentions() { return mentions; }
}
