package com.nocobase.comment.dto;

import java.util.UUID;

/**
 * 评论创建请求。
 */
public record CommentCreateRequest(
        String content,
        String contentType,
        UUID parentId,
        UUID attachmentId
) {}
