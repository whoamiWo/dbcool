package com.nocobase.comment.dto;

import com.nocobase.comment.RecordCommentEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 评论视图（对外输出）。
 *
 * <p>{@code replies} 仅在父评论上填充；子回复的 replies 恒为空列表。
 */
public record CommentView(
        UUID id,
        String collectionName,
        String recordId,
        UUID parentId,
        UUID authorId,
        String authorName,
        String content,
        String contentType,
        List<RecordCommentEntity.MentionDto> mentions,
        UUID attachmentId,
        Instant editedAt,
        Instant deletedAt,
        Instant createdAt,
        List<CommentView> replies,
        int replyCount
) {}
