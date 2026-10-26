package com.nocobase.comment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 评论仓储。
 *
 * <p><b>所有查询必须带 tenantId 条件</b>，防止跨租户越权。
 */
@Repository
public interface RecordCommentRepository extends JpaRepository<RecordCommentEntity, UUID> {

    /**
     * 顶层评论（parentId 为空）首屏查询，按 created_at DESC 排序。
     */
    List<RecordCommentEntity> findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(
            String collectionName, String recordId, String tenantId, Pageable pageable);

    /**
     * 顶层评论游标分页：只取早于 {@code cursor} 的记录（created_at 严格小于）。
     *
     * <p>用游标而非 OFFSET，避免深分页 O(n) 退化。
     */
    List<RecordCommentEntity> findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
            String collectionName, String recordId, String tenantId, Instant cursor, Pageable pageable);

    /** 评论总数（不含已删除，含子回复）。 */
    long countByCollectionNameAndRecordIdAndTenantIdAndDeletedAtIsNull(
            String collectionName, String recordId, String tenantId);

    /**
     * 某父评论下的子回复（按 created_at ASC）。
     *
     * <p>不过滤 deleted_at：被删回复以占位形式展示，保持线程上下文完整。
     */
    List<RecordCommentEntity> findByParentIdAndTenantIdOrderByCreatedAtAsc(
            UUID parentId, String tenantId);

    /** 某记录下所有子回复的 parentId 集合（批量装配，避免 N+1）。 */
    @Query("""
            select distinct c.parentId from RecordCommentEntity c
            where c.tenantId = :tenantId
              and c.collectionName = :collectionName
              and c.recordId = :recordId
              and c.parentId is not null
            """)
    List<UUID> findParentIdsWithReplies(@Param("tenantId") String tenantId,
                                        @Param("collectionName") String collectionName,
                                        @Param("recordId") String recordId);

    /** 批量查询指定父评论下的全部子回复（避免 N+1）。 */
    List<RecordCommentEntity> findByParentIdInAndTenantIdOrderByCreatedAtAsc(
            List<UUID> parentIds, String tenantId);
}
