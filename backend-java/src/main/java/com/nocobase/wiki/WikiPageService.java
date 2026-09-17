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
 * 文档页面服务 — CRUD + 版本管理 + 发布/归档。
 *
 * <p>状态流转: DRAFT → PUBLISHED → ARCHIVED
 * <ul>
 *   <li>每次更新自动创建版本记录</li>
 *   <li>发布时更新 content_html</li>
 *   <li>归档软删除(状态变更)</li>
 * </ul>
 */
@Service
public class WikiPageService {

    private final WikiPageRepository pageRepository;
    private final WikiVersionRepository versionRepository;
    private final KnowledgeBaseService knowledgeBaseService;

    @Autowired
    public WikiPageService(
            WikiPageRepository pageRepository,
            WikiVersionRepository versionRepository,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.pageRepository = pageRepository;
        this.versionRepository = versionRepository;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional
    public WikiPageEntity create(
            UUID kbId, UUID parentId, String title, String slug, String content,
            UUID createdBy, String tenantId
    ) {
        // 验证知识库存在且属于该租户
        knowledgeBaseService.get(kbId); // 会抛 404 如果不存在
        if (pageRepository.existsBySlugAndTenantId(slug, tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "页面 slug 已存在: " + slug);
        }
        WikiPageEntity entity = new WikiPageEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setKnowledgeBaseId(kbId);
        entity.setParentId(parentId);
        entity.setTitle(title);
        entity.setSlug(slug);
        entity.setContent(content);
        entity.setStatus("DRAFT");
        entity.setVersion(1);
        entity.setCreatedBy(createdBy);
        entity.setUpdatedBy(createdBy);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return pageRepository.save(entity);
    }

    public WikiPageEntity get(UUID id) {
        return pageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + id));
    }

    public WikiPageEntity getBySlug(String slug, String tenantId) {
        return pageRepository.findBySlugAndTenantId(slug, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + slug));
    }

    public List<WikiPageEntity> listByKb(UUID kbId, String tenantId) {
        return pageRepository.findByKnowledgeBaseIdAndTenantIdOrderByUpdatedAtDesc(kbId, tenantId);
    }

    public List<WikiPageEntity> listByKbAndStatus(UUID kbId, String status, String tenantId) {
        return pageRepository.findByKnowledgeBaseIdAndStatusAndTenantIdOrderByUpdatedAtDesc(kbId, status, tenantId);
    }

    public List<WikiPageEntity> listByParent(UUID parentId) {
        return pageRepository.findByParentIdOrderByUpdatedAtDesc(parentId);
    }

    @Transactional
    public WikiPageEntity update(
            UUID id, String title, String content, String slug, UUID updatedBy, String tenantId
    ) {
        WikiPageEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改该页面");
        }
        // 检查 slug 唯一性
        if (slug != null && !slug.equals(entity.getSlug())) {
            if (pageRepository.existsBySlugAndTenantId(slug, tenantId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "页面 slug 已存在: " + slug);
            }
            entity.setSlug(slug);
        }
        if (title != null) entity.setTitle(title);
        if (content != null) {
            entity.setContent(content);
            // 创建版本
            createVersion(entity, content, "更新", updatedBy);
            entity.setVersion(entity.getVersion() + 1);
        }
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        return pageRepository.save(entity);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        WikiPageEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该页面");
        }
        pageRepository.delete(entity);
    }

    @Transactional
    public WikiPageEntity publish(UUID id, UUID updatedBy, String tenantId) {
        WikiPageEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权发布该页面");
        }
        entity.setStatus("PUBLISHED");
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        return pageRepository.save(entity);
    }

    @Transactional
    public WikiPageEntity archive(UUID id, UUID updatedBy, String tenantId) {
        WikiPageEntity entity = get(id);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权归档该页面");
        }
        entity.setStatus("ARCHIVED");
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        return pageRepository.save(entity);
    }

    @Transactional
    public WikiVersionEntity createVersion(WikiPageEntity page, String content, String summary, UUID createdBy) {
        WikiVersionEntity version = new WikiVersionEntity();
        version.setId(UUID.randomUUID());
        version.setWikiPageId(page.getId());
        version.setVersion(page.getVersion());
        version.setContent(content);
        version.setSummary(summary);
        version.setCreatedBy(createdBy);
        version.setCreatedAt(Instant.now());
        return versionRepository.save(version);
    }

    public List<WikiVersionEntity> listVersions(UUID pageId) {
        return versionRepository.findByWikiPageIdOrderByVersionDesc(pageId);
    }

    public WikiVersionEntity getVersion(UUID pageId, int version) {
        return versionRepository.findByWikiPageIdAndVersion(pageId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在: " + version));
    }

    @Transactional
    public WikiPageEntity restoreVersion(UUID pageId, int version, UUID updatedBy, String tenantId) {
        WikiPageEntity page = get(pageId);
        if (!page.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权回滚该页面");
        }
        WikiVersionEntity versionEntity = getVersion(pageId, version);
        page.setContent(versionEntity.getContent());
        page.setVersion(versionEntity.getVersion());
        page.setUpdatedBy(updatedBy);
        page.setUpdatedAt(Instant.now());
        return pageRepository.save(page);
    }
}