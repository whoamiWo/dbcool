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
     * 游标分页(取首页):主消息(parentId 为空)按时间正序。
     * 用游标而非 OFFSET,避免深翻页性能退化。
     */
    List<ImMessageEntity> findByChannelIdAndParentIdIsNullOrderByCreatedAtAsc(
            UUID channelId, Pageable pageable);

    /** 游标分页(续页):严格晚于 cursor,避免边界重复。 */
    List<ImMessageEntity> findByChannelIdAndParentIdIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID channelId, Instant cursor, Pageable pageable);

    /** 线程回复。 */
    List<ImMessageEntity> findByParentIdOrderByCreatedAtAsc(UUID parentId);

    /** 未读计数:晚于游标且未删除的主消息数。 */
    long countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull(
            UUID channelId, Instant cursor);

    /** 关键字搜索(大小写不敏感)。 */
    @Query("select m from ImMessageEntity m where m.channelId = :channelId "
            + "and m.deletedAt is null "
            + "and lower(m.content) like lower(concat('%', :kw, '%')) "
            + "order by m.createdAt desc")
    List<ImMessageEntity> search(@Param("channelId") UUID channelId,
                                 @Param("kw") String kw,
                                 Pageable pageable);
}
