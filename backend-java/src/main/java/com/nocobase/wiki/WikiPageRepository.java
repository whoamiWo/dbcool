package com.nocobase.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    List<WikiPageEntity> findByKnowledgeBaseIdAndParentIdAndTenantIdOrderByCreatedAtAsc(UUID knowledgeBaseId, UUID parentId, String tenantId);

    List<WikiPageEntity> findByParentIdOrderByUpdatedAtDesc(UUID parentId);

    List<WikiPageEntity> findByIsTemplateTrueAndTenantId(String tenantId);

    List<WikiPageEntity> findByDeletedAtIsNotNullAndTenantIdOrderByDeletedAtDesc(String tenantId);

    Optional<WikiPageEntity> findByShareToken(String shareToken);

    /** 反向引用：找出所有引用了指定 slug 的页面（按内容 LIKE 匹配 [[slug]]）。 */
    @Query(value = """
        SELECT p FROM WikiPageEntity p
        WHERE p.tenantId = :tenantId
          AND p.content LIKE :pattern
        ORDER BY p.updatedAt DESC
        """)
    List<WikiPageEntity> listBacklinks(@Param("pattern") String pattern, @Param("tenantId") String tenantId);

    boolean existsBySlugAndTenantId(String slug, String tenantId);

    /**
     * 原生 SQL 增加版本号 (避免 JPA 缓存问题)。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE wiki_page SET version = version + 1, updated_at = NOW() WHERE id = :id", nativeQuery = true)
    void bumpVersion(@Param("id") UUID id);

    /**
     * FTS 全文检索 — PostgreSQL 使用 to_tsvector + plainto_tsquery, H2 使用 LIKE 兼容。
     * 仅返回匹配查询的页面,按相关度排序。
     */
    @Query(value = """
        SELECT p FROM WikiPageEntity p
        WHERE p.tenantId = :tenantId
          AND (p.content ILIKE %:query% OR p.title ILIKE %:query%)
        ORDER BY p.updatedAt DESC
        """)
    List<WikiPageEntity> searchByContent(@Param("query") String query, @Param("tenantId") String tenantId);

    /**
     * 按知识库 + FTS 检索 (兼容 H2)。
     */
    @Query(value = """
        SELECT p FROM WikiPageEntity p
        WHERE p.knowledgeBaseId = :kbId
          AND p.tenantId = :tenantId
          AND (p.content ILIKE %:query% OR p.title ILIKE %:query%)
        ORDER BY p.updatedAt DESC
        """)
    List<WikiPageEntity> searchByContentAndKb(@Param("query") String query, @Param("kbId") UUID kbId, @Param("tenantId") String tenantId);

    }