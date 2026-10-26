package com.nocobase.wiki;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WikiPageService.class);

    // 支持 [[slug|anchor]] 语法的正则：第二捕获组可选，兼容旧语法
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]\\|]+)(?:\\|([^\\]]+))?\\]\\]");
    
    // 上下文提取窗口大小（前后各取字符数）
    private static final int CONTEXT_WINDOW_SIZE = 80;
    // 上下文最大长度（含省略号）
    private static final int MAX_CONTEXT_LENGTH = 1024;

    /** 链接引用信息：包含 slug 与锚文本。 */
    public record LinkRef(String slug, String anchorText, int matchStart, int matchEnd) {}

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

    /** PHASE95: 带租户校验版本 —— 对外一律走这个，跨租户访问返回 404。 */
    public WikiPageEntity get(UUID id, String tenantId) {
        return pageRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + id));
    }

    public WikiPageEntity getBySlug(String slug, String tenantId) {
        return pageRepository.findBySlugAndTenantId(slug, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在: " + slug));
    }

    /**
     * 双向链接同步：解析内容中的 [[slug]] / [[slug|anchor]] → 查目标页 → 写入 wiki_backlink。
     *
     * <p>P0-4a 接真：parseBacklinks 解析方法已存在但从未被调用，导致 wiki_backlink 表
     * 永远为空，反向链接查询恒返空。先清旧再插新（幂等）。
     *
     * <p>PHASE130：同时落库锚文本与引用处上下文摘录，供反向链接面板展示。
     * 同一 slug 多次引用时取首次出现的锚文本与上下文。
     */
    @Transactional
    public void syncBacklinks(UUID sourcePageId, String tenantId, String content) {
        if (sourcePageId == null || content == null) return;
        backlinkRepository.deleteBySourcePageId(sourcePageId);
        List<LinkRef> refs = parseBacklinkRefs(content);
        if (refs.isEmpty()) return;
        // 按 slug 去重，保留首次出现的锚文本与上下文
        Map<String, LinkRef> uniq = new LinkedHashMap<>();
        for (LinkRef ref : refs) {
            uniq.putIfAbsent(ref.slug(), ref);
        }
        Instant now = Instant.now();
        for (Map.Entry<String, LinkRef> entry : uniq.entrySet()) {
            String slug = entry.getKey();
            LinkRef ref = entry.getValue();
            UUID targetId = pageRepository.findBySlugAndTenantId(slug, tenantId)
                    .map(WikiPageEntity::getId).orElse(null);
            WikiBacklinkEntity link = new WikiBacklinkEntity();
            link.setId(UUID.randomUUID());
            link.setSourcePageId(sourcePageId);
            link.setTargetPageId(targetId); // null 表示目标尚未创建
            link.setTargetSlug(slug);
            link.setAnchorText(ref.anchorText());
            try {
                link.setContext(extractContext(content, ref.matchStart(), ref.matchEnd()));
            } catch (RuntimeException ex) {
                // 仅记录页面与 slug，不打印正文，避免日志膨胀与敏感信息泄漏
                log.warn("提取反向链接上下文失败 pageId={} slug={}", sourcePageId, slug, ex);
                link.setContext(null);
            }
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

    /** 解析内容中的 [[页面名]] 双向链接，返回链接目标 slug 列表（旧签名，保留供其他调用点使用）。 */
    public List<String> parseBacklinks(String content) {
        return parseBacklinkRefs(content).stream()
                .map(LinkRef::slug)
                .distinct()
                .toList();
    }

    /**
     * 结构化解析：返回每个 [[slug]] 或 [[slug|anchor]] 的 slug、锚文本与匹配区间。
     * 第二捕获组可选，天然兼容旧语法；锚文本缺省时回落为 null。
     */
    public List<LinkRef> parseBacklinkRefs(String content) {
        if (content == null) return List.of();
        Matcher m = WIKI_LINK_PATTERN.matcher(content);
        List<LinkRef> result = new ArrayList<>();
        while (m.find()) {
            String slug = m.group(1).trim();
            String anchor = m.group(2) != null ? m.group(2).trim() : null;
            result.add(new LinkRef(slug, anchor, m.start(), m.end()));
        }
        return result;
    }

    /**
     * 上下文摘录提取：以链接匹配区间为中心，向前后各取 CONTEXT_WINDOW_SIZE 字符，
     * 剥离 Markdown 标记与换行，压缩连续空白，超长截断并按需加省略号。
     */
    String extractContext(String content, int matchStart, int matchEnd) {
        if (content == null || content.isEmpty()) return null;
        int start = Math.max(0, matchStart - CONTEXT_WINDOW_SIZE);
        int end = Math.min(content.length(), matchEnd + CONTEXT_WINDOW_SIZE);
        String raw = content.substring(start, end);
        // 先将 [[slug|anchor]] / [[slug]] 替换为可读显示文本，避免暴露原始语法
        String display = WIKI_LINK_PATTERN.matcher(raw)
                .replaceAll(mr -> Matcher.quoteReplacement(
                        mr.group(2) != null ? mr.group(2).trim() : mr.group(1).trim()));
        // 剥离 Markdown 标记：标题 #、代码 `、引用 >、链接/强调符号
        String cleaned = display
                .replaceAll("[#`>\\[\\]()]", "")    // 移除标题/代码/引用/括号
                .replaceAll("[*_~]", "")            // 移除强调/删除线
                .replaceAll("\\s+", " ")            // 压缩连续空白
                .trim();
        if (cleaned.isEmpty()) return null;
        // 未超长：首尾按需加省略号
        if (cleaned.length() <= MAX_CONTEXT_LENGTH) {
            if (start > 0) cleaned = "…" + cleaned;
            if (end < content.length()) cleaned = cleaned + "…";
            return cleaned;
        }
        // 超长：以链接位置为中心截取窗口
        int linkCenter = Math.max(0, Math.min(matchStart - start, cleaned.length()));
        int cs = Math.max(0, Math.min(linkCenter - MAX_CONTEXT_LENGTH / 2, cleaned.length() - MAX_CONTEXT_LENGTH));
        int ce = Math.min(cleaned.length(), cs + MAX_CONTEXT_LENGTH);
        String truncated = cleaned.substring(cs, ce);
        if (cs > 0) truncated = "…" + truncated;
        if (ce < cleaned.length()) truncated = truncated + "…";
        return truncated;
    }

    /** 反向引用查询：找出所有引用了指定 slug 的页面。 */
    public List<WikiPageEntity> listBacklinks(String slug, String tenantId) {
        return pageRepository.listBacklinks("%[[" + slug + "]]%", tenantId);
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
        // 模板归属校验
        if (!tpl.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Template not owned by tenant");
        }
        // 将模板内容复制到新页面
        return create(kbId, parentId, title, slug, tpl.getContent(), createdBy, tenantId);
    }

    /** 软删除（进入回收站，30 天可恢复）。 */
    @Transactional
    public WikiPageEntity softDelete(UUID id, String tenantId) {
        WikiPageEntity e = get(id);
        if (!e.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
        e.setDeletedAt(Instant.now());
        e.setStatus("TRASH");
        return pageRepository.save(e);
    }

    /** 恢复软删除的页面。 */
    @Transactional
    public WikiPageEntity restore(UUID id, String tenantId) {
        WikiPageEntity e = get(id);
        if (!e.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
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
    public WikiPageEntity share(UUID id, boolean regenerate, String tenantId) {
        WikiPageEntity e = get(id);
        if (!e.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
        if (regenerate || e.getShareToken() == null) {
            e.setShareToken(UUID.randomUUID().toString().substring(0, 32));
        }
        return pageRepository.save(e);
    }

    /** 撤销分享链接。 */
    @Transactional
    public WikiPageEntity unshare(UUID id, String tenantId) {
        WikiPageEntity e = get(id);
        if (!e.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
        e.setShareToken(null);
        return pageRepository.save(e);
    }
}