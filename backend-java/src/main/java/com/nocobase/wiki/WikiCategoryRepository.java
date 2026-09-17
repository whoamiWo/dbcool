package com.nocobase.wiki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 分类 Repository — 树形结构查询。
 */
@Repository
public interface WikiCategoryRepository extends JpaRepository<WikiCategoryEntity, UUID> {

    List<WikiCategoryEntity> findByKnowledgeBaseIdOrderBySortOrderAscCreatedAtAsc(UUID knowledgeBaseId);

    List<WikiCategoryEntity> findByKnowledgeBaseIdAndTenantIdOrderBySortOrderAscCreatedAtAsc(UUID knowledgeBaseId, String tenantId);

    List<WikiCategoryEntity> findByParentIdOrderBySortOrderAscCreatedAtAsc(UUID parentId);

    Optional<WikiCategoryEntity> findByKnowledgeBaseIdAndSlug(UUID knowledgeBaseId, String slug);

    boolean existsByKnowledgeBaseIdAndSlug(UUID knowledgeBaseId, String slug);

    // 递归查询子分类(用于删除时级联检查)
    @Query("SELECT c FROM WikiCategoryEntity c WHERE c.knowledgeBaseId = :kbId AND c.parentId IN (SELECT c2.id FROM WikiCategoryEntity c2 WHERE c2.parentId = :parentId)")
    List<WikiCategoryEntity> findDescendantsByParentId(UUID kbId, UUID parentId);
}