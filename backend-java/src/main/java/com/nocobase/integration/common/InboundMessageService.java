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
import java.util.Optional;
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
    private final ExternalUserMappingRepository externalUserMappingRepository;
    private final com.nocobase.auth.UserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Value("${integration.inbound.default-channel-id:}")
    private String defaultChannelId;

    @Value("${integration.inbound.enabled:true}")
    private boolean inboundEnabled;

    @Value("${integration.inbound.auto-provision-user:false}")
    private boolean autoProvisionUser;

    public InboundMessageService(ImMessageRepository messageRepository,
                                  ImChannelMemberRepository memberRepository,
                                  RedisStompBridge bridge,
                                  ExternalMessageLogRepository externalMessageLogRepository,
                                  ExternalUserMappingRepository externalUserMappingRepository,
                                  com.nocobase.auth.UserRepository userRepository,
                                  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.bridge = bridge;
        this.externalMessageLogRepository = externalMessageLogRepository;
        this.externalUserMappingRepository = externalUserMappingRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
     * 映射第三方用户 → 我方用户（PHASE96 T1 真实现）。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>查 {@code external_user_mapping}（platform + externalUserId + tenantId）</li>
     *   <li>命中且 local_user_id 非空且该用户仍属于本租户 → 返回该用户</li>
     *   <li>未命中：
     *       <ul>
     *         <li>{@code integration.inbound.auto-provision-user=true} → 自动建号并回写映射</li>
     *         <li>否则返回 {@code null}，由调用方丢弃该消息（显式失败，不再回落到硬编码 UUID）</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><b>绝不返回硬编码 UUID</b> —— 那会让所有第三方入站消息的作者变成同一个人，
     * 并使 {@code senderId == null} 的失败分支成为死代码。
     */
    private UUID mapExternalUser(String source, String externalUserId, String tenantId) {
        if (source == null || source.isBlank() || externalUserId == null || externalUserId.isBlank()) {
            log.warn("[Inbound] 映射参数不完整，拒绝映射：source={}, externalUserId={}", source, externalUserId);
            return null;
        }

        Optional<ExternalUserMappingEntity> existing =
                externalUserMappingRepository.findByPlatformAndExternalUserIdAndTenantId(
                        source, externalUserId, tenantId);
        if (existing.isPresent() && existing.get().getLocalUserId() != null) {
            UUID localUserId = existing.get().getLocalUserId();
            if (userRepository.findByIdAndTenantId(localUserId, tenantId).isPresent()) {
                return localUserId;
            }
            log.warn("[Inbound] 映射指向的用户已不存在或跨租户，作废映射：source={}, externalUserId={}, localUserId={}",
                    source, externalUserId, localUserId);
            return null;
        }

        if (!autoProvisionUser) {
            log.warn("[Inbound] 未找到外部用户映射且未开启自动建号，丢弃："
                    + "source={}, externalUserId={}, tenantId={}（可在 external_user_mapping 预置映射，"
                    + "或开启 integration.inbound.auto-provision-user）", source, externalUserId, tenantId);
            return null;
        }

        return provisionExternalUser(source, externalUserId, tenantId);
    }

    /** 自动创建本地用户并回写映射（仅在 auto-provision-user=true 时进入）。 */
    private UUID provisionExternalUser(String source, String externalUserId, String tenantId) {
        final String username;
        {
            String candidate = source + ":" + externalUserId;
            username = candidate.length() > 128 ? candidate.substring(0, 128) : candidate;
        }
        final com.nocobase.auth.UserEntity user =
                userRepository.findByUsername(username).orElseGet(() -> {
            com.nocobase.auth.UserEntity u = new com.nocobase.auth.UserEntity();
            u.setId(UUID.randomUUID());
            u.setUsername(username);
            // 第三方同步用户不得持有可本地登录的口令
            u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            u.setTenantId(tenantId);
            u.setDisplayName(username);
            u.setCreatedAt(java.time.Instant.now());
            return userRepository.save(u);
        });

        ExternalUserMappingEntity mapping = new ExternalUserMappingEntity();
        mapping.setId(UUID.randomUUID());
        mapping.setPlatform(source);
        mapping.setExternalUserId(externalUserId);
        mapping.setLocalUserId(user.getId());
        mapping.setTenantId(tenantId);
        mapping.setDisplayName(user.getDisplayName());
        mapping.setCreatedAt(java.time.Instant.now());
        mapping.setUpdatedAt(java.time.Instant.now());
        try {
            externalUserMappingRepository.save(mapping);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 并发下唯一键冲突：重新读取已存在的映射即可
            log.info("[Inbound] 映射已由并发请求创建，直接复用：source={}, externalUserId={}",
                    source, externalUserId);
        }
        log.info("[Inbound] 已自动创建外部用户映射：source={}, externalUserId={}, localUserId={}",
                source, externalUserId, user.getId());
        return user.getId();
    }
}
