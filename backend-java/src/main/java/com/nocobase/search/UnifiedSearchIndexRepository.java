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
 * 统一搜索索引 Repository — 基于 PostgreSQL FTS (tsvector/tsquery/ts_headline)。
 *
 * <p>触发器 V28 使用 'simple' 解析器，因此所有查询统一使用 'simple' 配置。
 */
@Repository
public interface UnifiedSearchIndexRepository extends JpaRepository<UnifiedSearchIndexEntity, UUID> {

    List<UnifiedSearchIndexEntity> findByEntityTypeAndTenantId(String entityType, String tenantId);

    List<UnifiedSearchIndexEntity> findByTenantId(String tenantId);

    List<UnifiedSearchIndexEntity> findByEntityTypeAndEntityIdAndTenantId(
            String entityType, String entityId, String tenantId);

    void deleteByEntityTypeAndEntityIdAndTenantId(
            String entityType, String entityId, String tenantId);

    /**
     * 按实体类型 + 关键词 FTS 检索，返回相关度分数与高亮片段。
     * 使用 PG 内置 ts_rank + ts_headline（配置 'simple' 与触发器一致）。
     */
    @Query(value = """
        SELECT e.entity_type AS entityType,
               e.entity_id AS entityId,
               e.tenant_id AS tenantId,
               e.title AS title,
               ts_headline('simple', e.content,
                   tsquery('simple', :keyword),
                   'StartSel=&lt;mark&gt;, StopSel=&lt;/mark&gt;, MaxWords=50, MinWords=20, MaxFragments=2, FragmentDelimiter= ... ')
                   AS snippet,
               ts_rank(e.content_tsv, tsquery('simple', :keyword)) AS rank,
               e.updated_at AS updatedAt,
               e.metadata AS metadataJson
        FROM unified_search_index e
        WHERE e.tenant_id = :tenantId
          AND e.entity_type = :entityType
          AND e.content_tsv @@ to_tsquery('simple', :keyword)
        ORDER BY rank DESC
        LIMIT :limit
        """,
        nativeQuery = true)
    List<UnifiedSearchIndexEntity> searchByKeywordAndType(
            @Param("entityType") String entityType,
            @Param("keyword") String keyword,
            @Param("tenantId") String tenantId,
            @Param("limit") int limit);

    /**
     * 无类型过滤的全局 FTS 检索。
     */
    @Query(value = """
        SELECT e.entity_type AS entityType,
               e.entity_id AS entityId,
               e.tenant_id AS tenantId,
               e.title AS title,
               ts_headline('simple', e.content,
                   tsquery('simple', :keyword),
                   'StartSel=&lt;mark&gt;, StopSel=&lt;/mark&gt;, MaxWords=50, MinWords=20, MaxFragments=2, FragmentDelimiter= ... ')
                   AS snippet,
               ts_rank(e.content_tsv, tsquery('simple', :keyword)) AS rank,
               e.updated_at AS updatedAt,
               e.metadata AS metadataJson
        FROM unified_search_index e
        WHERE e.tenant_id = :tenantId
          AND e.content_tsv @@ to_tsquery('simple', :keyword)
        ORDER BY rank DESC
        LIMIT :limit
        """,
        nativeQuery = true)
    List<UnifiedSearchIndexEntity> searchAllTypes(
            @Param("keyword") String keyword,
            @Param("tenantId") String tenantId,
            @Param("limit") int limit);

    /**
     * 统计某实体类型在租户下的索引数量（用于 facets）。
     */
    long countByEntityTypeAndTenantId(String entityType, String tenantId);
}
