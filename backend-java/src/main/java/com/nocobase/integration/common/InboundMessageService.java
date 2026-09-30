package com.nocobase.integration.common;

import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.realtime.RedisStompBridge;
import com.nocobase.realtime.StompDestinations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 第三方入站消息统一处理服务。
 *
 * <p>负责：
 * <ul>
 *   <li>幂等去重：基于第三方消息 ID（Slack ts/event_id, Mattermost post_id）</li>
 *   <li>映射转换：第三方 channel/user → 我方 channel/user</li>
 *   <li>落库 + 广播：写入 im_message 并通过 Stomp 推送到前端</li>
 * </ul>
 *
 * <p>防环机制：我方发到第三方、第三方回调回来时，通过 externalMessageId 识别并丢弃。
 */
@Service
public class InboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageService.class);

    private final ImMessageRepository messageRepository;
    private final ImChannelMemberRepository memberRepository;
    private final RedisStompBridge bridge;
    private final ExternalMessageLogRepository externalMessageLogRepository;

    @Value("${integration.inbound.default-channel-id:}")
    private String defaultChannelId;

    @Value("${integration.inbound.enabled:true}")
    private boolean inboundEnabled;

    public InboundMessageService(ImMessageRepository messageRepository,
                                  ImChannelMemberRepository memberRepository,
                                  RedisStompBridge bridge,
                                  ExternalMessageLogRepository externalMessageLogRepository) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.bridge = bridge;
        this.externalMessageLogRepository = externalMessageLogRepository;
    }

    /**
     * 处理第三方入站消息。
     *
     * @param tenantId 租户 ID
     * @param targetChannelId 目标我方频道 ID（可选，未提供时使用 defaultChannelId）
     * @param externalSource 第三方来源标识（如 "slack", "mattermost"）
     * @param externalMessageId 第三方消息唯一 ID（用于幂等去重）
     * @param externalUserId 第三方用户 ID
     * @param content 消息内容
     * @param senderName 发送者显示名称（可选）
     * @return 已保存的消息实体，null 表示被过滤（如回声消息、未配置映射）
     */
    @Transactional
    public ImMessageEntity processInboundMessage(String tenantId,
                                                  UUID targetChannelId,
                                                  String externalSource,
                                                  String externalMessageId,
                                                  String externalUserId,
                                                  String content,
                                                  String senderName) {
        if (!inboundEnabled) {
            log.warn("[Inbound] 入站消息已禁用，丢弃：source={}, externalId={}", externalSource, externalMessageId);
            return null;
        }

        if (externalMessageId == null || externalMessageId.isBlank()) {
            log.warn("[Inbound] 缺少 externalMessageId，丢弃：source={}", externalSource);
            return null;
        }

        // 1. 幂等检查：基于 externalSource + externalMessageId 组合键
        if (isDuplicate(externalSource, externalMessageId)) {
            log.debug("[Inbound] 检测到重复消息，跳过：source={}, externalId={}", externalSource, externalMessageId);
            return null;
        }

        // 2. 确定目标频道
        UUID channelId = resolveTargetChannel(targetChannelId);
        if (channelId == null) {
            log.warn("[Inbound] 未配置目标频道，丢弃：source={}, externalId={}", externalSource, externalMessageId);
            return null;
        }

        // 3. 映射第三方用户 → 我方用户
        UUID senderId = mapExternalUser(externalSource, externalUserId, tenantId);
        if (senderId == null) {
            log.warn("[Inbound] 用户映射失败，丢弃：source={}, externalUserId={}", externalSource, externalUserId);
            return null;
        }

        // 4. 校验发送者是频道成员
        if (!memberRepository.existsByChannelIdAndUserId(channelId, senderId)) {
            log.warn("[Inbound] 发送者不是频道成员，丢弃：channelId={}, senderId={}", channelId, senderId);
            return null;
        }

        // 5. 保存消息
        ImMessageEntity message = new ImMessageEntity();
        message.setId(UUID.randomUUID());
        message.setTenantId(tenantId);
        message.setChannelId(channelId);
        message.setSenderId(senderId);
        message.setContent(content != null ? content : "");
        message.setContentType("text");
        message.setCreatedAt(Instant.now());

        ImMessageEntity saved = messageRepository.save(message);

        // 6. 记录外部消息 ID 以防重复
        ExternalMessageLogEntity logEntry = new ExternalMessageLogEntity(externalSource, externalMessageId, tenantId);
        externalMessageLogRepository.save(logEntry);

        // 7. 广播到前端
        bridge.broadcast(StompDestinations.channelTopic(tenantId, channelId), saved);

        log.info("[Inbound] 入站消息已保存并广播：source={}, externalId={}, channelId={}, senderId={}",
                externalSource, externalMessageId, channelId, senderId);

        return saved;
    }

    /**
     * 检查消息是否已处理过（幂等）。
     */
    private boolean isDuplicate(String source, String externalMessageId) {
        return externalMessageLogRepository.findBySourceAndExternalMessageId(source, externalMessageId).isPresent();
    }

    /**
     * 解析目标频道 ID。
     */
    private UUID resolveTargetChannel(UUID providedChannelId) {
        if (providedChannelId != null) {
            return providedChannelId;
        }
        if (defaultChannelId != null && !defaultChannelId.isBlank()) {
            try {
                return UUID.fromString(defaultChannelId);
            } catch (IllegalArgumentException e) {
                log.warn("[Inbound] defaultChannelId 格式无效：{}", defaultChannelId);
            }
        }
        return null;
    }

    /**
     * 映射第三方用户 → 我方用户。
     *
     * <p>生产环境应查询 user_mapping 表或使用类似 DingTalk UserMappingService 的逻辑。
     * 当前简化实现：返回固定测试用户 ID（用于演示）。
     */
    private UUID mapExternalUser(String source, String externalUserId, String tenantId) {
        // TODO: 实现真实的用户映射逻辑
        // 当前返回一个固定的测试用户 ID（假设该用户已存在且是频道成员）
        // 生产环境应替换为查询数据库的映射表
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
