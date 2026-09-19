package com.nocobase.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * AI Agent Repository。
 */
@Repository
public interface AiAgentEntityRepository extends JpaRepository<AiAgentEntity, UUID> {

    @Query(value = """
        SELECT a FROM AiAgentEntity a
        WHERE a.tenantId = :tenantId AND a.channelId = :channelId
        """)
    List<AiAgentEntity> findByTenantIdAndChannelId(
            @Param("tenantId") String tenantId,
            @Param("channelId") UUID channelId);
}