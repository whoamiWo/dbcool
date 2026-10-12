package com.nocobase.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI Conversation Repository。
 */
@Repository
public interface AiConversationEntityRepository extends JpaRepository<AiConversationEntity, UUID> {

    /**
     * @deprecated 无租户过滤，跨租户会命中他人会话。请改用
     *             {@link #findByAgentIdAndChannelIdAndUserIdAndTenantId}。
     */
    @Deprecated
    @Query(value = """
        SELECT c FROM AiConversationEntity c
        WHERE c.agentId = :agentId AND c.channelId = :channelId AND c.userId = :userId
        """)
    List<AiConversationEntity> findByAgentIdAndChannelIdAndUserId(
            @Param("agentId") UUID agentId,
            @Param("channelId") UUID channelId,
            @Param("userId") UUID userId);

    /** PHASE95: 带租户过滤的会话查询 —— 唯一允许对外使用的会话入口。 */
    @Query(value = """
        SELECT c FROM AiConversationEntity c
        WHERE c.agentId = :agentId AND c.channelId = :channelId AND c.userId = :userId
          AND c.tenantId = :tenantId
        """)
    Optional<AiConversationEntity> findByAgentIdAndChannelIdAndUserIdAndTenantId(
            @Param("agentId") UUID agentId,
            @Param("channelId") UUID channelId,
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);

    Optional<AiConversationEntity> findByIdAndTenantId(UUID id, String tenantId);

    List<AiConversationEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}