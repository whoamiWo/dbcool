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

    /**
     * PHASE111：按「用户 + 租户」查询会话 —— 供 AI 会话匿名化后的残留校验使用。
     *
     * <p>为什么单独加：既有入口（含带租户的
     * {@link #findByAgentIdAndChannelIdAndUserIdAndTenantId}）都要求 agentId+channelId，
     * 无法仅按 userId 定位某个用户的全部会话，这正是
     * {@code UserDataErasureService#eraseAiConversations} 此前无法实现的原因。
     *
     * <p>租户条件不可省：即便调用方已做归属校验，仍在 SQL 层加 tenantId（纵深防御）。
     * 该查询依赖 {@code V57__ai_conversation_user_idx.sql} 建的
     * {@code idx_ai_conv_user(tenant_id, user_id)}，否则会全表扫。
     */
    @Query(value = """
        SELECT c FROM AiConversationEntity c
        WHERE c.userId = :userId AND c.tenantId = :tenantId
        """)
    List<AiConversationEntity> findByUserIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);

    /**
     * PHASE111：批量将该用户在本租户下的会话内容替换为占位（保留记录行，清除内容）。
     *
     * <p>与 {@code ImMessageRepository#anonymizeContentBySender}、
     * {@code AuditLogRepository#anonymizeUsername} 同构：单条 UPDATE 返回受影响行数，
     * 而非逐条 {@code save}（避免 N 次 flush 与全量实体加载）。
     *
     * <p><b>占位必须是合法 JSON</b>：{@code messages} 列是 {@code jsonb NOT NULL}，
     * 且 {@code AgentService#parseMessages} 期望 JSON 数组。约定传入 {@code "[]"}
     * （或带 role/content 的合法数组）。切勿传 {@code "[已删除]"} 这类非 JSON 文本，
     * 否则 jsonb 写入报错、或让下游解析静默降级成"看起来已删"。
     *
     * <p><b>必须在事务内调用</b>：{@code @Modifying} 的 UPDATE 脱离事务会抛
     * {@code InvalidDataAccessApiUsageException}（本仓库既有踩坑）。
     * {@code UserDataErasureService#erasesUserData} 已带 {@code @Transactional}。
     *
     * @return 受影响行数
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
        UPDATE AiConversationEntity c SET c.messagesJson = :placeholder
        WHERE c.userId = :userId AND c.tenantId = :tenantId
        """)
    int anonymizeMessagesByUserId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId,
            @Param("placeholder") String placeholder);
}