package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImMessageRepository extends JpaRepository<ImMessageEntity, UUID> {

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

    /** 关键字搜索 (大小写不敏感)。 */
    @Query("select m from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and lower(m.content) like lower(concat('%', :kw, '%')) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> search(@Param("channelId") UUID channelId,
                                 @Param("kw") String kw,
                                 Pageable pageable);

    /** 跨频道搜索 (频道可空，按租户 + 关键词过滤)。 */
    @Query("select m from ImMessageEntity m where m.tenantId = :tenantId "
            + "and m.deletedAt is null "
            + "and lower(m.content) like lower(concat('%', :kw, '%')) "
            + "and (:channelId is null or m.channelId = :channelId) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> searchCrossChannel(@Param("tenantId") String tenantId,
                                             @Param("channelId") UUID channelId,
                                             @Param("kw") String kw,
                                             Pageable pageable);

    /** 查询已过期且未删除的消息 (用于 Burn-on-Read 清理)。 */
    List<ImMessageEntity> findByExpiresAtBeforeAndDeletedAtIsNull(Instant now);

    /**
     * 高级搜索（支持多维度过滤）。
     *
     * @param channelId 频道 ID
     * @param keyword 关键词（LIKE 匹配）
     * @param mentionedBy 提及的用户 ID（可选，匹配 @{displayName}:userId）
     * @param senderId 发送者 ID（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     */
    @Query("select m from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and (:keyword is null or lower(m.content) like lower(concat('%', :keyword, '%'))) "
            + "and (:mentionedBy is null or m.content like '%:@{%' || :mentionedBy || '}%') "
            + "and (:senderId is null or m.senderId = :senderId) "
            + "and (:startTime is null or m.createdAt >= :startTime) "
            + "and (:endTime is null or m.createdAt <= :endTime) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> searchWithFilters(@Param("channelId") UUID channelId,
                                            @Param("keyword") String keyword,
                                            @Param("mentionedBy") UUID mentionedBy,
                                            @Param("senderId") UUID senderId,
                                            @Param("startTime") Instant startTime,
                                            @Param("endTime") Instant endTime,
                                            Pageable pageable);
}
