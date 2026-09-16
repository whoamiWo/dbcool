package com.nocobase.im.dto;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 消息传输对象(经 WebSocket 广播的载荷)。
 *
 * <p>删除消息仍会广播(带 deletedAt),以便客户端把该条渲染为"已删除"
 * 而不是从线程中凭空消失。
 */
public record ImMessageDto(
        UUID id,
        UUID channelId,
        UUID senderId,
        UUID parentId,
        String content,
        String contentType,
        Instant createdAt,
        Instant editedAt,
        Instant deletedAt
) {
    public static ImMessageDto from(ImMessageEntity e) {
        return new ImMessageDto(
                e.getId(),
                e.getChannelId(),
                e.getSenderId(),
                e.getParentId(),
                e.getContent(),
                e.getContentType(),
                e.getCreatedAt(),
                e.getEditedAt(),
                e.getDeletedAt()
        );
    }
}
