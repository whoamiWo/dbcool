package com.nocobase.wiki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 知识库 Repository — 复用 Spring Data JPA 标准查询。
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, UUID> {

    Optional<KnowledgeBaseEntity> findBySlug(String slug);

    Optional<KnowledgeBaseEntity> findBySlugAndTenantId(String slug, String tenantId);

    List<KnowledgeBaseEntity> findByTenantIdOrderBySortOrderAscCreatedAtAsc(String tenantId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndTenantId(String slug, String tenantId);
}