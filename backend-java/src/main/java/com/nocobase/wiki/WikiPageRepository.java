package com.nocobase.wiki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 文档页面 Repository — 支持按知识库、分类、状态、搜索查询。
 */
@Repository
public interface WikiPageRepository extends JpaRepository<WikiPageEntity, UUID> {

    Optional<WikiPageEntity> findBySlug(String slug);

    Optional<WikiPageEntity> findBySlugAndTenantId(String slug, String tenantId);

    Optional<WikiPageEntity> findBySlugAndKnowledgeBaseId(String slug, UUID knowledgeBaseId);

    List<WikiPageEntity> findByKnowledgeBaseIdAndTenantIdOrderByUpdatedAtDesc(UUID knowledgeBaseId, String tenantId);

    List<WikiPageEntity> findByKnowledgeBaseIdAndStatusAndTenantIdOrderByUpdatedAtDesc(UUID knowledgeBaseId, String status, String tenantId);

    List<WikiPageEntity> findByKnowledgeBaseIdAndParentIdAndTenantIdOrderBySortOrderAscCreatedAtAsc(UUID knowledgeBaseId, UUID parentId, String tenantId);

    List<WikiPageEntity> findByParentIdOrderByUpdatedAtDesc(UUID parentId);

    boolean existsBySlugAndTenantId(String slug, String tenantId);

    /**
     * FTS 全文检索 — 使用 PostgreSQL to_tsvector + plainto_tsquery。
     * 仅返回匹配查询的页面,按相关度排序。
     */
    @Query(value = """
        SELECT p FROM WikiPageEntity p
        WHERE p.tenantId = :tenantId
          AND p.contentTsv @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank_cd(p.contentTsv, plainto_tsquery('simple', :query)) DESC
        """)
    List<WikiPageEntity> searchByContent(@Param("query") String query, @Param("tenantId") String tenantId);

    /**
     * 按知识库 + FTS 检索。
     */
    @Query(value = """
        SELECT p FROM WikiPageEntity p
        WHERE p.knowledgeBaseId = :kbId
          AND p.tenantId = :tenantId
          AND p.contentTsv @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank_cd(p.contentTsv, plainto_tsquery('simple', :query)) DESC
        """)
    List<WikiPageEntity> searchByContentAndKb(@Param("query") String query, @Param("kbId") UUID kbId, @Param("tenantId") String tenantId);
}