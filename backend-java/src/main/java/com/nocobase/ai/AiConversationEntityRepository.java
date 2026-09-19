package com.nocobase.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * AI Conversation Repository。
 */
@Repository
public interface AiConversationEntityRepository extends JpaRepository<AiConversationEntity, UUID> {

    @Query(value = """
        SELECT c FROM AiConversationEntity c
        WHERE c.agentId = :agentId AND c.channelId = :channelId AND c.userId = :userId
        """)
    List<AiConversationEntity> findByAgentIdAndChannelIdAndUserId(
            @Param("agentId") UUID agentId,
            @Param("channelId") UUID channelId,
            @Param("userId") UUID userId);
}