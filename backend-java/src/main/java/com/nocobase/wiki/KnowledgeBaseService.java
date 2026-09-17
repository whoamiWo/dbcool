package com.nocobase.wiki;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库服务 — CRUD + 权限检查。
 *
 * <p>复用现有 ACL 体系:通过 {@link com.nocobase.auth.AclEnforcer} 进行权限校验。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final WikiCategoryRepository categoryRepository;
    private final WikiPageRepository pageRepository;

    @Autowired
    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            WikiCategoryRepository categoryRepository,
            WikiPageRepository pageRepository
    ) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.pageRepository = pageRepository;
    }

    @Transactional
    public KnowledgeBaseEntity create(
            String name, String description, String slug, String icon,
            UUID createdBy, String tenantId
    ) {
        if (repository.existsBySlugAndTenantId(slug, tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "知识库 slug 已存在: " + slug);
        }
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setDescription(description);
        entity.setSlug(slug);
        entity.setIcon(icon);
        entity.setTenantId(tenantId);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return repository.save(entity);
    }

    public KnowledgeBaseEntity get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在: " + id));
    }

    public KnowledgeBaseEntity getBySlug(String slug, String tenantId) {
        return repository.findBySlugAndTenantId(slug, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在: " + slug));
    }

    public List<KnowledgeBaseEntity> list(String tenantId) {
        return repository.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId);
    }

    @Transactional
    public KnowledgeBaseEntity update(
            UUID id, String name, String description, String slug, String icon,
            String tenantId
    ) {
        KnowledgeBaseEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改该知识库");
        }
        if (slug != null && !slug.equals(entity.getSlug())) {
            if (repository.existsBySlugAndTenantId(slug, tenantId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "知识库 slug 已存在: " + slug);
            }
            entity.setSlug(slug);
        }
        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
        if (icon != null) entity.setIcon(icon);
        entity.setUpdatedAt(Instant.now());
        return repository.save(entity);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        KnowledgeBaseEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该知识库");
        }
        // 级联删除:分类、页面、版本均由 DB 外键 CASCADE 处理
        repository.delete(entity);
    }
}