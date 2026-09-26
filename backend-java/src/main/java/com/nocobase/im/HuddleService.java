package com.nocobase.im;

import com.nocobase.im.entity.ImHuddleEntity;
import com.nocobase.im.entity.ImHuddleParticipantEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.ImChannelRepository;
import com.nocobase.im.ImHuddleRepository;
import com.nocobase.im.ImHuddleParticipantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Huddle 语音会话服务 — 参考 Slack Huddle 设计。
 *
 * <p>功能:
 * <ul>
 *   <li>创建/加入/离开语音会话</li>
 *   <li>管理参与者状态(静音、屏幕共享)</li>
 *   <li>会话生命周期管理</li>
 * </ul>
 *
 * <p><b>WebRTC 信令</b>:本服务仅管理会话元数据，实际音视频流通过
 * {@code /api/huddle/signaling} WebSocket 端点处理。
 */
@Service
public class HuddleService {

    private final ImHuddleRepository huddleRepository;
    private final ImHuddleParticipantRepository participantRepository;
    private final ImChannelRepository channelRepository;
    private final ImChannelMemberRepository channelMemberRepository;

    public HuddleService(ImHuddleRepository huddleRepository,
                         ImHuddleParticipantRepository participantRepository,
                         ImChannelRepository channelRepository,
                         ImChannelMemberRepository channelMemberRepository) {
        this.huddleRepository = huddleRepository;
        this.participantRepository = participantRepository;
        this.channelRepository = channelRepository;
        this.channelMemberRepository = channelMemberRepository;
    }

    /**
     * 创建语音会话。
     *
     * @param tenantId 租户 ID
     * @param userId   创建者
     * @param channelId 所属频道
     * @param name     会话名称(可选)
     * @return 创建的会话
     */
    @Transactional
    public ImHuddleEntity create(String tenantId, UUID userId, UUID channelId, String name) {
        // 校验频道存在且用户是成员
        channelRepository.findByIdAndTenantId(channelId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "频道不存在"));
        
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不是该频道成员");
        }

        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(UUID.randomUUID());
        huddle.setChannelId(channelId);
        huddle.setName(name);
        huddle.setStatus(ImHuddleEntity.Status.PENDING.name());
        huddle.setCreatedBy(userId);
        huddle.setTenantId(tenantId);
        huddle.setCreatedAt(Instant.now());
        
        ImHuddleEntity saved = huddleRepository.save(huddle);
        
        // 自动加入创建者
        addParticipant(saved.getId(), userId);
        
        return saved;
    }

    /**
     * 加入语音会话。
     */
    @Transactional
    public ImHuddleParticipantEntity join(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleEntity huddle = getHuddle(huddleId, tenantId);
        
        if (ImHuddleEntity.Status.ENDED.name().equals(huddle.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话已结束");
        }
        
        if (participantRepository.existsByHuddleIdAndUserId(huddleId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已在会话中");
        }
        
        return addParticipant(huddleId, userId);
    }

    /**
     * 离开语音会话。
     */
    @Transactional
    public void leave(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleEntity huddle = getHuddle(huddleId, tenantId);
        ImHuddleParticipantEntity participant = participantRepository
                .findByHuddleIdAndUserId(huddleId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "不在会话中"));
        
        participant.setLeftAt(Instant.now());
        participantRepository.save(participant);
        
        // 如果所有参与者都离开了，结束会话
        checkAndEndHuddle(huddle);
    }

    /**
     * 结束语音会话。
     */
    @Transactional
    public ImHuddleEntity end(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleEntity huddle = getHuddle(huddleId, tenantId);
        
        // 只有创建者或主持人可以结束会话
        if (!huddle.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权结束会话");
        }
        
        huddle.setStatus(ImHuddleEntity.Status.ENDED.name());
        huddle.setEndedAt(Instant.now());
        ImHuddleEntity saved = huddleRepository.save(huddle);
        
        // 标记所有参与者为离开
        List<ImHuddleParticipantEntity> participants = 
                participantRepository.findByHuddleId(huddleId);
        Instant now = Instant.now();
        for (ImHuddleParticipantEntity p : participants) {
            if (p.getLeftAt() == null) {
                p.setLeftAt(now);
                participantRepository.save(p);
            }
        }
        
        return saved;
    }

    /**
     * 切换静音状态。
     */
    @Transactional
    public ImHuddleParticipantEntity toggleMute(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleParticipantEntity participant = getParticipant(huddleId, userId, tenantId);
        participant.setIsMuted(!participant.getIsMuted());
        return participantRepository.save(participant);
    }

    /**
     * 切换屏幕共享状态。
     */
    @Transactional
    public ImHuddleParticipantEntity toggleScreenShare(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleParticipantEntity participant = getParticipant(huddleId, userId, tenantId);
        participant.setIsScreenSharing(!participant.getIsScreenSharing());
        return participantRepository.save(participant);
    }

    /**
     * 获取活跃会话列表。
     */
    public List<ImHuddleEntity> listActive(String tenantId, UUID channelId) {
        return huddleRepository.findByTenantIdAndChannelIdAndStatusNot(
                tenantId, channelId, ImHuddleEntity.Status.ENDED.name());
    }

    /**
     * 获取会话详情。
     */
    public ImHuddleEntity get(UUID huddleId, String tenantId) {
        return getHuddle(huddleId, tenantId);
    }

    /**
     * 获取会话参与者列表。
     */
    public List<ImHuddleParticipantEntity> listParticipants(UUID huddleId, String tenantId) {
        // 归属校验:参与者实体无 tenantId 字段,通过父实体(huddle)间接隔离
        getHuddle(huddleId, tenantId);
        return participantRepository.findByHuddleId(huddleId);
    }

    // ============================================================
    //  内部方法
    // ============================================================

    private ImHuddleEntity getHuddle(UUID huddleId, String tenantId) {
        return huddleRepository.findByIdAndTenantId(huddleId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private ImHuddleParticipantEntity getParticipant(UUID huddleId, UUID userId, String tenantId) {
        ImHuddleEntity huddle = getHuddle(huddleId, tenantId);
        return participantRepository.findByHuddleIdAndUserId(huddleId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "不在会话中"));
    }

    private ImHuddleParticipantEntity addParticipant(UUID huddleId, UUID userId) {
        ImHuddleParticipantEntity participant = new ImHuddleParticipantEntity();
        participant.setId(UUID.randomUUID());
        participant.setHuddleId(huddleId);
        participant.setUserId(userId);
        participant.setIsMuted(false);
        participant.setIsScreenSharing(false);
        participant.setJoinedAt(Instant.now());
        
        ImHuddleParticipantEntity saved = participantRepository.save(participant);
        
        // 如果会话是 PENDING 状态，自动开始
        ImHuddleEntity huddle = huddleRepository.findById(huddleId).orElse(null);
        if (huddle != null && ImHuddleEntity.Status.PENDING.name().equals(huddle.getStatus())) {
            huddle.setStatus(ImHuddleEntity.Status.ACTIVE.name());
            huddle.setStartedAt(Instant.now());
            huddleRepository.save(huddle);
        }
        
        return saved;
    }

    private void checkAndEndHuddle(ImHuddleEntity huddle) {
        List<ImHuddleParticipantEntity> activeParticipants = 
                participantRepository.findByHuddleIdAndLeftAtIsNull(huddle.getId());
        
        if (activeParticipants.isEmpty()) {
            huddle.setStatus(ImHuddleEntity.Status.ENDED.name());
            huddle.setEndedAt(Instant.now());
            huddleRepository.save(huddle);
        }
    }
}
