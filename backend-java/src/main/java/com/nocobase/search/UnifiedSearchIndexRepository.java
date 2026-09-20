package com.nocobase.search;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一搜索索引 Repository。
 */
@Repository
public interface UnifiedSearchIndexRepository extends JpaRepository<UnifiedSearchIndexEntity, UUID> {

    List<UnifiedSearchIndexEntity> findByEntityTypeAndTenantId(String entityType, String tenantId);

    List<UnifiedSearchIndexEntity> findByTenantId(String tenantId);

    /**
     * 按 entity_type + entity_id 查找（用于 upsert 判断）。
     */
    List<UnifiedSearchIndexEntity> findByEntityTypeAndEntityIdAndTenantId(
            String entityType, String entityId, String tenantId);

    /**
     * 删除某实体的所有索引。
     */
    void deleteByEntityTypeAndEntityIdAndTenantId(
            String entityType, String entityId, String tenantId);

    /**
     * FTS 全文检索（兼容 H2 用 LIKE，PG 用 tsvector）。
     */
    @Query(value = """
        SELECT e FROM UnifiedSearchIndexEntity e
        WHERE e.tenantId = :tenantId
          AND (e.contentTsv ILIKE %:query% OR e.title ILIKE %:query%)
        ORDER BY e.updatedAt DESC
        """)
    List<UnifiedSearchIndexEntity> searchByKeyword(@Param("query") String query,
                                                    @Param("tenantId") String tenantId);

    /**
     * 按类型过滤搜索。
     */
    @Query(value = """
        SELECT e FROM UnifiedSearchIndexEntity e
        WHERE e.tenantId = :tenantId
          AND e.entityType = :entityType
          AND (e.contentTsv ILIKE %:query% OR e.title ILIKE %:query%)
        ORDER BY e.updatedAt DESC
        """)
    List<UnifiedSearchIndexEntity> searchByKeywordAndType(@Param("query") String query,
                                                            @Param("tenantId") String tenantId,
                                                            @Param("entityType") String entityType);
}