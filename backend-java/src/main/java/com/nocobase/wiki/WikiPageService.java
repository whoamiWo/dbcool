package com.nocobase.wiki;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档页面服务 — CRUD + 版本管理 + 发布/归档 + 权限过滤。
 *
 * <p>状态流转: DRAFT → PUBLISHED → ARCHIVED
 * <ul>
 *   <li>每次更新自动创建版本记录</li>
 *   <li>发布时更新 content_html</li>
 *   <li>归档软删除(状态变更)</li>
 *   <li>权限过滤: 复用 ACL 系统实现字段级/记录级控制</li>
 * </ul>
 */
@Service
public class WikiPageService {

    private final WikiPageRepository pageRepository;
    private final WikiVersionRepository versionRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final WikiPermissionService permissionService;
    private final WikiBacklinkRepository backlinkRepository;

    @Autowired
    public WikiPageService(
            WikiPageRepository pageRepository,
            WikiVersionRepository versionRepository,
            KnowledgeBaseService knowledgeBaseService,
            WikiPermissionService permissionService,
            WikiBacklinkRepository backlinkRepository
    ) {
        this.pageRepository = pageRepository;
        this.versionRepository = versionRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.permissionService = permissionService;
        this.backlinkRepository = backlinkRepository;
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
        WikiPageEntity saved = pageRepository.save(entity);
        // 同步双向链接：解析 [[slug]] → 写入 wiki_backlink
        if (content != null) syncBacklinks(saved.getId(), tenantId, content);
        return saved;
    }

    public WikiPageEntity get(UUID id) {
        return pageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + id));
    }

    public WikiPageEntity getBySlug(String slug, String tenantId) {
        return pageRepository.findBySlugAndTenantId(slug, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + slug));
    }

    /**
     * 双向链接同步：解析内容中的 [[slug]] → 查目标页 → 写入 wiki_backlink。
     *
     * <p>P0-4a 接真：parseBacklinks 解析方法已存在但从未被调用，导致 wiki_backlink 表
     * 永远为空，反向链接查询恒返空。先清旧再插新（幂等）。
     */
    @Transactional
    public void syncBacklinks(UUID sourcePageId, String tenantId, String content) {
        if (sourcePageId == null || content == null) return;
        backlinkRepository.deleteBySourcePageId(sourcePageId);
        List<String> slugs = parseBacklinks(content);
        if (slugs.isEmpty()) return;
        java.util.Set<String> uniq = new java.util.LinkedHashSet<>(slugs);
        Instant now = Instant.now();
        for (String slug : uniq) {
            UUID targetId = pageRepository.findBySlugAndTenantId(slug, tenantId)
                    .map(WikiPageEntity::getId).orElse(null);
            WikiBacklinkEntity link = new WikiBacklinkEntity();
            link.setId(UUID.randomUUID());
            link.setSourcePageId(sourcePageId);
            link.setTargetPageId(targetId); // null 表示目标尚未创建
            link.setTargetSlug(slug);
            link.setTenantId(tenantId);
            link.setCreatedAt(now);
            backlinkRepository.save(link);
        }
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
        // 记录级权限检查: 确认操作用户有权修改该页面
        permissionService.assertPagePermission(updatedBy, tenantId, entity.getId(), 
                WikiPermissionService.Action.UPDATE);
        
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
            // 先保存旧版本快照
            createVersion(entity, entity.getContent(), "更新", updatedBy, entity.getVersion());
            entity.setContent(content);
            entity.setVersion(entity.getVersion() + 1);
        }
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        pageRepository.saveAndFlush(entity);
        pageRepository.bumpVersion(entity.getId());
        // 双向链接同步：仅当内容变化时重建关系表
        if (content != null) syncBacklinks(entity.getId(), tenantId, content);
        return entity;
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        WikiPageEntity entity = get(id);
        permissionService.assertPagePermission(entity.getUpdatedBy(), tenantId, entity.getId(), 
                WikiPermissionService.Action.DELETE);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该页面");
        }
        pageRepository.delete(entity);
    }

    @Transactional
    public WikiPageEntity publish(UUID id, UUID updatedBy, String tenantId) {
        WikiPageEntity entity = get(id);
        permissionService.assertPagePermission(updatedBy, tenantId, entity.getId(), 
                WikiPermissionService.Action.UPDATE);
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
        permissionService.assertPagePermission(updatedBy, tenantId, entity.getId(), 
                WikiPermissionService.Action.UPDATE);
        if (!entity.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权归档该页面");
        }
        entity.setStatus("ARCHIVED");
        entity.setUpdatedBy(updatedBy);
        entity.setUpdatedAt(Instant.now());
        return pageRepository.save(entity);
    }

    @Transactional
    public WikiVersionEntity createVersion(WikiPageEntity page, String content, String summary, UUID createdBy, int versionNumber) {
        WikiVersionEntity version = new WikiVersionEntity();
        version.setId(UUID.randomUUID());
        version.setWikiPageId(page.getId());
        version.setVersion(versionNumber);
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
        // 版本号不变，仅内容回滚
        page.setUpdatedBy(updatedBy);
        page.setUpdatedAt(Instant.now());
        return pageRepository.save(page);
    }

    /** 过滤页面 DTO 的可写字段（根据 ACL FIELD policy） */
    public Set<String> filterWritableFields(UUID userId, String tenantId, UUID pageId) {
        return permissionService.filterWritableFields(userId, tenantId, "wiki_page", 
                WikiPermissionService.Action.UPDATE);
    }

    /** 过滤页面记录（隐藏不可读字段） */
    public Map<String, Object> filterPageRecord(UUID userId, String tenantId, Map<String, Object> record) {
        return permissionService.filterPageRecord(userId, tenantId, record);
    }

    // ============================================================
    //  Notion 剩余能力（Week 46）
    // ============================================================

    /** 解析内容中的 [[页面名]] 双向链接，返回链接目标列表。 */
    public List<String> parseBacklinks(String content) {
        if (content == null) return List.of();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
        java.util.regex.Matcher m = p.matcher(content);
        List<String> result = new ArrayList<>();
        while (m.find()) result.add(m.group(1).trim());
        return result;
    }

    /** 反向引用查询：找出所有引用了指定 slug 的页面。 */
    public List<WikiPageEntity> listBacklinks(String slug, String tenantId) {
        return pageRepository.listBacklinks("%[[" + slug + "]]%", tenantId);
    }

    /** 标记页面为模板。 */
    @Transactional
    public WikiPageEntity markTemplate(UUID id, boolean isTemplate) {
        WikiPageEntity e = get(id);
        e.setIsTemplate(isTemplate);
        return pageRepository.save(e);
    }

    /** 标记页面为模板（带租户校验）。 */
    @Transactional
    public WikiPageEntity markTemplate(UUID id, boolean isTemplate, String tenantId) {
        WikiPageEntity e = get(id);
        if (!e.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        e.setIsTemplate(isTemplate);
        return pageRepository.save(e);
    }

    /** 列出所有模板页面。 */
    public List<WikiPageEntity> listTemplates(String tenantId) {
        return pageRepository.findByIsTemplateTrueAndTenantId(tenantId);
    }

    /** 从模板创建新页面。 */
    @Transactional
    public WikiPageEntity createFromTemplate(UUID templateId, UUID kbId, UUID parentId,
                                             String title, String slug, UUID createdBy, String tenantId) {
        WikiPageEntity tpl = get(templateId);
        return create(kbId, parentId, title, slug, tpl.getContent(), createdBy, tenantId);
    }

    /** 软删除（进入回收站，30 天可恢复）。 */
    @Transactional
    public WikiPageEntity softDelete(UUID id) {
        WikiPageEntity e = get(id);
        e.setDeletedAt(Instant.now());
        e.setStatus("TRASH");
        return pageRepository.save(e);
    }

    /** 恢复软删除的页面。 */
    @Transactional
    public WikiPageEntity restore(UUID id) {
        WikiPageEntity e = get(id);
        e.setDeletedAt(null);
        e.setStatus("DRAFT");
        return pageRepository.save(e);
    }

    /** 列出回收站中的页面（按 deletedAt 降序）。 */
    public List<WikiPageEntity> listTrash(String tenantId) {
        return pageRepository.findByDeletedAtIsNotNullAndTenantIdOrderByDeletedAtDesc(tenantId);
    }

    /** 生成分享链接 token（免登录只读访问）。 */
    @Transactional
    public WikiPageEntity share(UUID id, boolean regenerate) {
        WikiPageEntity e = get(id);
        if (regenerate || e.getShareToken() == null) {
            e.setShareToken(UUID.randomUUID().toString().substring(0, 32));
        }
        return pageRepository.save(e);
    }

    /** 撤销分享链接。 */
    @Transactional
    public WikiPageEntity unshare(UUID id) {
        WikiPageEntity e = get(id);
        e.setShareToken(null);
        return pageRepository.save(e);
    }
}