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
 * 分类服务 — 树形结构管理。
 */
@Service
public class WikiCategoryService {

    private final WikiCategoryRepository categoryRepository;
    private final KnowledgeBaseService knowledgeBaseService;

    @Autowired
    public WikiCategoryService(
            WikiCategoryRepository categoryRepository,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.categoryRepository = categoryRepository;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional
    public WikiCategoryEntity create(
            UUID kbId, UUID parentId, String name, String slug, String tenantId
    ) {
        knowledgeBaseService.get(kbId); // 验证知识库存在
        if (categoryRepository.existsByKnowledgeBaseIdAndSlug(kbId, slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类 slug 已存在: " + slug);
        }
        WikiCategoryEntity entity = new WikiCategoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setKnowledgeBaseId(kbId);
        entity.setParentId(parentId);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setSortOrder(0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return categoryRepository.save(entity);
    }

    public List<WikiCategoryEntity> tree(UUID kbId) {
        return categoryRepository.findByKnowledgeBaseIdOrderBySortOrderAscCreatedAtAsc(kbId);
    }

    public WikiCategoryEntity get(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在: " + id));
    }

    @Transactional
    public WikiCategoryEntity update(UUID id, String name, String slug, UUID parentId, String tenantId) {
        WikiCategoryEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改该分类");
        }
        if (name != null) entity.setName(name);
        if (slug != null) entity.setSlug(slug);
        if (parentId != null) entity.setParentId(parentId);
        entity.setUpdatedAt(Instant.now());
        return categoryRepository.save(entity);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        WikiCategoryEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该分类");
        }
        categoryRepository.delete(entity);
    }
}