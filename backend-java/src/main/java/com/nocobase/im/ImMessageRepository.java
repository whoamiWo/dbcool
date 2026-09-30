package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImMessageRepository extends JpaRepository<ImMessageEntity, UUID>,
        JpaSpecificationExecutor<ImMessageEntity> {

    /**
     * 游标分页 (取首页):主消息 (parentId 为空) 按时间正序。
     * 用游标而非 OFFSET，避免深翻页性能退化。
     */
    List<ImMessageEntity> findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(
            UUID channelId, Pageable pageable);

    /** 游标分页 (续页):严格晚于 cursor，避免边界重复。 */
    List<ImMessageEntity> findByChannelIdAndParentIdIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID channelId, Instant cursor, Pageable pageable);

    /** 线程回复。 */
    List<ImMessageEntity> findByParentIdOrderByCreatedAtAsc(UUID parentId);

    /** 未读计数：晚于游标且未删除的主消息数。 */
    long countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull(
            UUID channelId, Instant cursor);

    /** 关键字搜索 (大小写不敏感，ILIKE)。 */
    @Query("select m from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and m.content ilike concat('%', :kw, '%') "
            + "order by m.createdAt desc")
    List<ImMessageEntity> search(@Param("channelId") UUID channelId,
                                 @Param("kw") String kw,
                                 Pageable pageable);

    /** 跨频道搜索 (频道可空，按租户 + 关键词过滤，ILIKE)。 */
    @Query("select m from ImMessageEntity m where m.tenantId = :tenantId "
            + "and m.deletedAt is null "
            + "and m.content ilike concat('%', :kw, '%') "
            + "and (:channelId is null or m.channelId = :channelId) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> searchCrossChannel(@Param("tenantId") String tenantId,
                                             @Param("channelId") UUID channelId,
                                             @Param("kw") String kw,
                                             Pageable pageable);

    /** 查询已过期且未删除的消息 (用于 Burn-on-Read 清理)。 */
    List<ImMessageEntity> findByExpiresAtBeforeAndDeletedAtIsNull(Instant now);

    /**
     * 高级搜索（支持多维度过滤，所有过滤在查询侧完成，先过滤再分页）。
     * 注意：关键词匹配使用 LIKE（%kw% 前缀通配，全表扫描），未接 tsvector 全文索引；
     * 历史 V43 tsvector 迁移已删除（R2 明确决策：避免 Flyway 事务与 GIN 死索引）。
     *
     * @param channelId 频道 ID
     * @param keyword 关键词（可选）
     * @param mentionedByStr 被提及用户 ID 字符串（可选）
     * @param senderId 发送者 ID（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     */
    @Query(value = "select m from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and (:senderId is null or m.senderId = :senderId) "
            + "and (:keyword is null or :keyword = '' or m.content is not null and lower(m.content) like lower(concat('%', :keyword, '%'))) "
            + "and (:mentionedByStr is null or m.mentions is not null and m.mentions like concat('%', :mentionedByStr, '%')) "
            + "and (:startTime is null or m.createdAt >= :startTime) "
            + "and (:endTime is null or m.createdAt <= :endTime) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> searchWithFullFilters(@Param("channelId") UUID channelId,
                                                @Param("keyword") String keyword,
                                                @Param("mentionedByStr") String mentionedByStr,
                                                @Param("senderId") UUID senderId,
                                                @Param("startTime") Instant startTime,
                                                @Param("endTime") Instant endTime,
                                                Pageable pageable);

    /**
     * 高级搜索计数（与 searchWithFullFilters 的过滤条件一致）。
     */
    @Query("select count(m) from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and (:senderId is null or m.senderId = :senderId) "
            + "and (:keyword is null or :keyword = '' or m.content is not null and lower(m.content) like lower(concat('%', :keyword, '%'))) "
            + "and (:mentionedByStr is null or m.mentions is not null and m.mentions like concat('%', :mentionedByStr, '%')) "
            + "and (:startTime is null or m.createdAt >= :startTime) "
            + "and (:endTime is null or m.createdAt <= :endTime)")
    long countWithFullFilters(@Param("channelId") UUID channelId,
                              @Param("keyword") String keyword,
                              @Param("mentionedByStr") String mentionedByStr,
                              @Param("senderId") UUID senderId,
                              @Param("startTime") Instant startTime,
                              @Param("endTime") Instant endTime);

    /**
     * 跨频道搜索计数 (ILIKE).
     */
    @Query("select count(m) from ImMessageEntity m where m.tenantId = :tenantId "
            + "and m.deletedAt is null "
            + "and m.content ilike concat('%', :kw, '%') "
            + "and (:channelId is null or m.channelId = :channelId)")
    long countCrossChannel(@Param("tenantId") String tenantId,
                           @Param("channelId") UUID channelId,
                           @Param("kw") String kw);

    /**
     * 查询用户被提及的消息（跨频道）。
     */
    @Query("select m from ImMessageEntity m where m.tenantId = :tenantId "
            + "and m.deletedAt is null "
            + "and m.mentions is not null and m.mentions like concat('%', :userId, '%') "
            + "and (:channelId is null or m.channelId = :channelId) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> findMentions(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("channelId") UUID channelId,
                                       Pageable pageable);

    /**
     * 查询用户被提及的消息数量（跨频道）。
     */
    @Query("select count(m) from ImMessageEntity m where m.tenantId = :tenantId "
            + "and m.deletedAt is null "
            + "and m.mentions is not null and m.mentions like concat('%', :userId, '%') "
            + "and (:channelId is null or m.channelId = :channelId)")
    long countMentions(@Param("tenantId") String tenantId,
                       @Param("userId") String userId,
                       @Param("channelId") UUID channelId);
}
