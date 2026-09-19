package com.nocobase.im;

import com.nocobase.im.dto.ImMessageDto;
import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.realtime.RedisStompBridge;
import com.nocobase.realtime.StompDestinations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 消息服务:发送、编辑、软删除、线程回复、搜索、已读与未读。
 *
 * <p>写操作完成后通过 {@link RedisStompBridge} 广播到 {@code /topic/t-<tenant>.channel.<id>},
 * 由总线负责本实例投递 + 跨实例转发,controller 不直接操作 WebSocket。
 */
@Service
public class MessageService {

    private final ImMessageRepository messageRepository;
    private final ImChannelMemberRepository memberRepository;
    private final RedisStompBridge bridge;

    public MessageService(ImMessageRepository messageRepository,
                          ImChannelMemberRepository memberRepository,
                          RedisStompBridge bridge) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.bridge = bridge;
    }

    /**
     * 游标分页拉取主消息(不含线程回复)。
     *
     * @param cursor 上一页最后一条的 createdAt;null 表示首页
     */
    public List<ImMessageEntity> list(UUID channelId, Instant cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        PageRequest page = PageRequest.of(0, safeLimit);
        return cursor == null
                ? messageRepository.findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(channelId, page)
                : messageRepository.findByChannelIdAndParentIdIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
                        channelId, cursor, page);
    }

    /** 线程回复列表。 */
    public List<ImMessageEntity> thread(UUID parentId) {
        return messageRepository.findByParentIdOrderByCreatedAtAsc(parentId);
    }

    @Transactional
    public ImMessageEntity send(String tenantId, UUID channelId, UUID senderId,
                                String content, String contentType, UUID parentId) {
        assertMember(channelId, senderId);
        String body = requireContent(content);

        if (parentId != null) {
            ImMessageEntity parent = messageRepository.findById(parentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "父消息不存在"));
            if (!parent.getChannelId().equals(channelId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "线程父消息不属于该频道");
            }
        }

        ImMessageEntity m = new ImMessageEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(channelId);
        m.setSenderId(senderId);
        m.setParentId(parentId);
        m.setContent(body);
        m.setContentType(contentType == null || contentType.isBlank() ? "text" : contentType);
        m.setTenantId(tenantId);

        ImMessageEntity saved = messageRepository.save(m);
        bridge.broadcast(StompDestinations.channelTopic(tenantId, channelId), ImMessageDto.from(saved));
        return saved;
    }

    /** 编辑(仅本人)。 */
    @Transactional
    public ImMessageEntity edit(String tenantId, UUID messageId, UUID userId, String content) {
        ImMessageEntity m = mustGet(messageId);
        assertOwner(m, userId);
        m.setContent(requireContent(content));
        m.setEditedAt(Instant.now());
        ImMessageEntity saved = messageRepository.save(m);
        bridge.broadcast(StompDestinations.channelTopic(tenantId, m.getChannelId()),
                ImMessageDto.from(saved != null ? saved : m));
        return saved != null ? saved : m;
    }

    /**
     * 软删除(仅本人)。保留线程完整性:
     * 父消息被删时,子回复仍应可见并提示"回复了已删除消息"。
     */
    @Transactional
    public void delete(String tenantId, UUID messageId, UUID userId) {
        ImMessageEntity m = mustGet(messageId);
        assertOwner(m, userId);
        m.setDeletedAt(Instant.now());
        ImMessageEntity saved = messageRepository.save(m);
        // 防御性:save 返回 null 时回退到入参实体,避免广播时 NPE
        bridge.broadcast(StompDestinations.channelTopic(tenantId, m.getChannelId()),
                ImMessageDto.from(saved != null ? saved : m));
    }

    public List<ImMessageEntity> search(UUID channelId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return messageRepository.search(channelId, keyword, PageRequest.of(0, safeLimit));
    }

    /**
     * 跨频道搜索（带租户 + 频道成员过滤，防越权读取他频道消息）。
     *
     * <p>SQL 侧已按 tenantId 过滤；此处再叠加「仅返回当前用户已加入频道的消息」，
     * 避免搜到同租户下用户非成员的私频道内容。
     *
     * @param channelId 可选，指定时只搜该频道（仍校验成员身份）
     */
    public List<ImMessageEntity> searchCrossChannel(String tenantId, UUID userId,
                                                     UUID channelId, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        // 指定频道时先校验成员身份，非成员直接返回空
        if (channelId != null && !memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            return List.of();
        }
        List<UUID> joined = memberRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .map(ImChannelMemberEntity::getChannelId)
                .toList();
        if (joined.isEmpty()) return List.of();
        return messageRepository
                .searchCrossChannel(tenantId, channelId, keyword, PageRequest.of(0, safeLimit))
                .stream()
                .filter(m -> joined.contains(m.getChannelId()))
                .toList();
    }

    /** 未读主消息数:以成员的 lastReadMessageId 对应时间为游标。 */
    public long unreadCount(UUID channelId, UUID userId) {
        Optional<ImChannelMemberEntity> m =
                memberRepository.findByChannelIdAndUserId(channelId, userId);
        if (m.isEmpty()) return 0L;
        Instant cursor = lastReadCursor(m.get());
        return cursor == null
                ? messageRepository.countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull(
                        channelId, Instant.EPOCH)
                : messageRepository.countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull(
                        channelId, cursor);
    }

    /** 标记已读到某条消息(推进未读游标)。 */
    @Transactional
    public void markRead(UUID channelId, UUID userId, UUID lastMessageId) {
        ImChannelMemberEntity m = memberRepository.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "不是频道成员"));
        m.setLastReadMessageId(lastMessageId);
        m.setLastReadAt(Instant.now());
        memberRepository.save(m);
    }

    public ImMessageEntity mustGet(UUID messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
    }

    private Instant lastReadCursor(ImChannelMemberEntity m) {
        UUID lastId = m.getLastReadMessageId();
        if (lastId == null) return null; // 从未读过 → 全部视为未读
        return messageRepository.findById(lastId).map(ImMessageEntity::getCreatedAt).orElse(null);
    }

    private void assertMember(UUID channelId, UUID userId) {
        if (!memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不是频道成员");
        }
    }

    private static void assertOwner(ImMessageEntity m, UUID userId) {
        if (!m.getSenderId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能操作自己的消息");
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
        return content;
    }
}
