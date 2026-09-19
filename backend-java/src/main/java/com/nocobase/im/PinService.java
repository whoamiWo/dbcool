package com.nocobase.im;

import com.nocobase.im.entity.ImPinEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.ImPinRepository;
import com.nocobase.im.ImMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息置顶服务。
 */
@Service
public class PinService {

    private final ImPinRepository pinRepository;
    private final ImMessageRepository messageRepository;

    public PinService(ImPinRepository pinRepository,
                      ImMessageRepository messageRepository) {
        this.pinRepository = pinRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 置顶某条消息。
     *
     * @return 置顶记录;若已置顶则返回现有记录
     */
    @Transactional
    public ImPinEntity pin(String tenantId, UUID channelId,
                           UUID messageId, UUID pinnedBy) {
        ImPinEntity existing = pinRepository
                .findByTenantIdAndChannelIdAndMessageIdAndUnpinnedAtIsNull(
                        tenantId, channelId, messageId)
                .orElse(null);
        if (existing != null) return existing;

        // 排除软删除消息
        ImMessageEntity msg = messageRepository.findById(messageId)
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalStateException("消息不存在或已删除"));

        ImPinEntity pin = new ImPinEntity();
        pin.setId(UUID.randomUUID());
        pin.setTenantId(tenantId);
        pin.setChannelId(channelId);
        pin.setMessageId(messageId);
        pin.setPinnedBy(pinnedBy);
        return pinRepository.save(pin);
    }

    /** 取消置顶。 */
    @Transactional
    public void unpin(String tenantId, UUID channelId, UUID messageId) {
        pinRepository.softUnpin(tenantId, channelId, messageId, Instant.now());
    }

    /** 某频道所有有效置顶(按时间倒序)。 */
    public List<ImPinEntity> listActive(String tenantId, UUID channelId) {
        return pinRepository.findActivePins(tenantId, channelId);
    }

    /** 判断某消息是否被置顶。 */
    public boolean isPinned(String tenantId, UUID channelId, UUID messageId) {
        return pinRepository
                .findByTenantIdAndChannelIdAndMessageIdAndUnpinnedAtIsNull(
                        tenantId, channelId, messageId)
                .isPresent();
    }
}