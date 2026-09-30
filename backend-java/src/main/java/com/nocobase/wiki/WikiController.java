package com.nocobase.wiki;

import com.nocobase.wiki.WikiTemplateService;
import com.nocobase.wiki.WikiBacklinkRepository;
import com.nocobase.wiki.WikiBacklinkEntity;
import com.nocobase.wiki.WikiEmbeddingService;
import com.nocobase.ai.AiAssistantService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.event.RecordChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wiki REST API — 知识库/文档/分类/搜索/版本。
 *
 * <p>复用现有: {@link AclEnforcer} 权限、{@link AuditService} 审计、
 * {@link RecordChangeEvent} 事件发布(供工作流消费)。
 */
@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private static final Logger log = LoggerFactory.getLogger(WikiController.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final WikiPageService pageService;
    private final WikiCategoryService categoryService;
    private final WikiSearchService searchService;
    private final AclEnforcer aclEnforcer;
    private final WikiPermissionService permissionService;
    private final WikiAttachmentService attachmentService;
    private final WikiBlockService blockService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final WikiTemplateService templateService;
    private final WikiBacklinkRepository backlinkRepository;
    private final WikiEmbeddingService embeddingService;
    private final AiAssistantService aiAssistantService;

    public WikiController(
            KnowledgeBaseService knowledgeBaseService,
            WikiPageService pageService,
            WikiCategoryService categoryService,
            WikiSearchService searchService,
            AclEnforcer aclEnforcer,
            WikiPermissionService permissionService,
            WikiAttachmentService attachmentService,
            WikiBlockService blockService,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher,
            WikiTemplateService templateService,
            WikiBacklinkRepository backlinkRepository,
            WikiEmbeddingService embeddingService,
            AiAssistantService aiAssistantService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.pageService = pageService;
        this.categoryService = categoryService;
        this.searchService = searchService;
        this.aclEnforcer = aclEnforcer;
        this.permissionService = permissionService;
        this.attachmentService = attachmentService;
        this.blockService = blockService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.templateService = templateService;
        this.backlinkRepository = backlinkRepository;
        this.embeddingService = embeddingService;
        this.aiAssistantService = aiAssistantService;
    }

    // ============================================================
    //  知识库 CRUD
    // ============================================================

    @PostMapping("/kb")
    public ResponseEntity<Map<String, Object>> createKb(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String name = (String) body.get("name");
        String slug = (String) body.get("slug");
        String description = (String) body.get("description");
        String icon = (String) body.get("icon");
        if (name == null || name.isBlank() || slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 和 slug 必填");
        }
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "knowledge_base",
                com.nocobase.auth.AclPolicyEntity.Action.CREATE);
        KnowledgeBaseEntity entity = knowledgeBaseService.create(
                name, description, slug, icon, user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "CREATE", "knowledge_base", entity.getId().toString(), body);
        eventPublisher.publishEvent(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "knowledge_base",
                entity.getId().toString(), body, user.tenantId(), user.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toKbDto(entity)));
    }

    @GetMapping("/kb")
    public Map<String, Object> listKb(@AuthenticationPrincipal AuthenticatedUser user) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "knowledge_base",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        List<KnowledgeBaseEntity> list = knowledgeBaseService.list(user.tenantId());
        return Map.of("code", 0, "message", "success", "data",
                list.stream().map(this::toKbDto).toList());
    }

    @GetMapping("/kb/{id}")
    public Map<String, Object> getKb(@PathVariable UUID id,
                                     @AuthenticationPrincipal AuthenticatedUser user) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "knowledge_base",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        KnowledgeBaseEntity entity = knowledgeBaseService.get(id);
        if (!entity.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        }
        return Map.of("code", 0, "message", "success", "data", toKbDto(entity));
    }

    @PutMapping("/kb/{id}")
    public Map<String, Object> updateKb(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "knowledge_base",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        String name = (String) body.get("name");
        String slug = (String) body.get("slug");
        String description = (String) body.get("description");
        String icon = (String) body.get("icon");
        KnowledgeBaseEntity entity = knowledgeBaseService.update(id, name, description, slug, icon, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "UPDATE", "knowledge_base", id.toString(), body);
        return Map.of("code", 0, "message", "success", "data", toKbDto(entity));
    }

    @DeleteMapping("/kb/{id}")
    public Map<String, Object> deleteKb(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "knowledge_base",
                com.nocobase.auth.AclPolicyEntity.Action.DELETE);
        KnowledgeBaseEntity entity = knowledgeBaseService.get(id);
        if (!entity.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除");
        }
        knowledgeBaseService.delete(id, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "DELETE", "knowledge_base", id.toString(), null);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id.toString()));
    }

    // ============================================================
    //  文档页面 CRUD
    // ============================================================

    @PostMapping("/pages")
    public ResponseEntity<Map<String, Object>> createPage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String kbIdStr = (String) body.get("knowledge_base_id");
        String slug = (String) body.get("slug");
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        String parentIdStr = (String) body.get("parent_id");
        if (kbIdStr == null || slug == null || slug.isBlank() || title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledge_base_id, slug, title 必填");
        }
        UUID kbId = UUID.fromString(kbIdStr);
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.CREATE);
        WikiPageEntity entity = pageService.create(kbId, parentId, title, slug, content,
                user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "CREATE", "wiki_page", entity.getId().toString(), body);
        eventPublisher.publishEvent(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "wiki_page",
                entity.getId().toString(), body, user.tenantId(), user.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toPageDto(entity)));
    }

    @GetMapping("/pages")
    public Map<String, Object> listPages(
            @RequestParam(required = false) UUID kbId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        List<WikiPageEntity> list;
        if (kbId != null) {
            list = status != null
                    ? pageService.listByKbAndStatus(kbId, status, user.tenantId())
                    : pageService.listByKb(kbId, user.tenantId());
        } else {
            list = pageService.listByKb(null, user.tenantId());
        }
        int start = (page - 1) * size;
        int end = Math.min(start + size, list.size());
        List<Map<String, Object>> paged = list.subList(start, end).stream()
                .map(this::toPageDto).toList();
        return Map.of(
                "code", 0, "message", "success",
                "data", paged,
                "total", list.size(),
                "page", page,
                "size", size);
    }

    @GetMapping("/pages/{id}")
    public Map<String, Object> getPage(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        WikiPageEntity entity = pageService.get(id);
        if (!entity.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        }
        return Map.of("code", 0, "message", "success", "data", toPageDto(entity));
    }

    /**
     * 按 slug 获取页面（P0 修复：前端 /wiki/:slug 三页面统一入口）。
     * 注意：路径必须在 /pages/{id} 之前，避免 Spring 把 "by-slug" 当成 UUID id。
     */
    @GetMapping("/pages/by-slug/{slug}")
    public Map<String, Object> getPageBySlug(
            @PathVariable String slug,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        WikiPageEntity entity = pageService.getBySlug(slug, user.tenantId());
        return Map.of("code", 0, "message", "success", "data", toPageDto(entity));
    }

    @PutMapping("/pages/{id}")
    public Map<String, Object> updatePage(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        String title = (String) body.get("title");
        String slug = (String) body.get("slug");
        String content = (String) body.get("content");
        WikiPageEntity entity = pageService.update(id, title, content, slug,
                user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "UPDATE", "wiki_page", id.toString(), body);
        eventPublisher.publishEvent(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.UPDATE, "wiki_page",
                id.toString(), body, user.tenantId(), user.userId()));
        return Map.of("code", 0, "message", "success", "data", toPageDto(entity));
    }

    @DeleteMapping("/pages/{id}")
    public Map<String, Object> deletePage(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.DELETE);
        WikiPageEntity entity = pageService.get(id);
        if (!entity.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除");
        }
        pageService.delete(id, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "DELETE", "wiki_page", id.toString(), null);
        eventPublisher.publishEvent(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.DELETE, "wiki_page",
                id.toString(), null, user.tenantId(), user.userId()));
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id.toString()));
    }

    // ============================================================
    //  发布/归档
    // ============================================================

    @PostMapping("/pages/{id}/publish")
    public Map<String, Object> publishPage(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        WikiPageEntity entity = pageService.publish(id, user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "PUBLISH", "wiki_page", id.toString(), null);
        eventPublisher.publishEvent(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.UPDATE, "wiki_page",
                id.toString(), Map.of("status", "PUBLISHED"),
                user.tenantId(), user.userId()));
        return Map.of("code", 0, "message", "published", "data", toPageDto(entity));
    }

    @PostMapping("/pages/{id}/archive")
    public Map<String, Object> archivePage(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        WikiPageEntity entity = pageService.archive(id, user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "ARCHIVE", "wiki_page", id.toString(), null);
        return Map.of("code", 0, "message", "archived", "data", toPageDto(entity));
    }

    // ============================================================
    //  版本管理
    // ============================================================

    @GetMapping("/pages/{id}/versions")
    public Map<String, Object> listVersions(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        WikiPageEntity page = pageService.get(id);
        if (!page.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        }
        List<WikiVersionEntity> versions = pageService.listVersions(id);
        return Map.of("code", 0, "message", "success",
                "data", versions.stream().map(this::toVersionDto).toList());
    }

    @GetMapping("/pages/{id}/versions/{version}")
    public Map<String, Object> getVersion(
            @PathVariable UUID id,
            @PathVariable int version,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        WikiPageEntity page = pageService.get(id);
        if (!page.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        }
        WikiVersionEntity v = pageService.getVersion(id, version);
        return Map.of("code", 0, "message", "success", "data", toVersionDto(v));
    }

    @PostMapping("/pages/{id}/versions/{version}/restore")
    public Map<String, Object> restoreVersion(
            @PathVariable UUID id,
            @PathVariable int version,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        WikiPageEntity entity = pageService.restoreVersion(id, version, user.userId(), user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "RESTORE", "wiki_page", id.toString(), Map.of("version", version));
        return Map.of("code", 0, "message", "restored", "data", toPageDto(entity));
    }

    // ============================================================
    //  Block 管理（Notion 式块级内容）
    // ============================================================

    @GetMapping("/pages/{id}/blocks")
    public Map<String, Object> listBlocks(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        List<WikiBlockEntity> blocks = blockService.getBlocksByPageId(id);
        return Map.of("code", 0, "message", "success",
                "data", blocks.stream().map(this::toBlockDto).toList());
    }

    @PostMapping("/pages/{id}/blocks")
    public Map<String, Object> createBlocks(
            @PathVariable UUID id,
            @RequestBody List<Map<String, Object>> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        List<WikiBlockEntity> blocks = blockService.createBlocksForPage(id, body, user.tenantId(), user.userId());
        return Map.of("code", 0, "message", "created",
                "data", blocks.stream().map(this::toBlockDto).toList());
    }

    @PutMapping("/blocks/{blockId}")
    public Map<String, Object> updateBlock(
            @PathVariable UUID blockId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_block",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        WikiBlockEntity block = blockService.moveBlock(blockId,
                (UUID) body.get("parent_id"),
                body.containsKey("sort_order") ? (Integer) body.get("sort_order") : null,
                user.tenantId());
        return Map.of("code", 0, "message", "updated", "data", toBlockDto(block));
    }

    @DeleteMapping("/blocks/{blockId}")
    public Map<String, Object> deleteBlock(
            @PathVariable UUID blockId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_block",
                com.nocobase.auth.AclPolicyEntity.Action.DELETE);
        blockService.deleteBlocksByPageId(blockId, user.tenantId());
        return Map.of("code", 0, "message", "deleted");
    }

    @PutMapping("/pages/{id}/blocks/reorder")
    public Map<String, Object> reorderBlocks(
            @PathVariable UUID id,
            @RequestBody List<UUID> blockIds,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        List<WikiBlockEntity> blocks = blockService.reorderBlocks(id, blockIds, user.tenantId());
        return Map.of("code", 0, "message", "reordered",
                "data", blocks.stream().map(this::toBlockDto).toList());
    }

    @PostMapping("/blocks/batch-upsert")
    public Map<String, Object> batchUpsertBlocks(
            @RequestBody BatchUpsertRequest req,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        List<WikiBlockEntity> blocks = blockService.batchUpsertBlocks(
                req.pageId(),
                req.blocks().stream()
                        .map(b -> {
                            Map<String, Object> m = new HashMap<String, Object>();
                            m.put("type", b.type());
                            m.put("content", b.content());
                            if (b.language() != null) m.put("language", b.language());
                            if (b.checked() != null) m.put("checked", b.checked());
                            return m;
                        }).toList(),
                user.tenantId(), user.userId());
        return Map.of("code", 0, "message", "saved",
                "data", blocks.stream().map(this::toBlockDto).toList());
    }

    private Map<String, Object> toBlockDto(WikiBlockEntity block) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", block.getId().toString());
        dto.put("page_id", block.getPageId().toString());
        dto.put("parent_id", block.getParentId() != null ? block.getParentId().toString() : null);
        dto.put("type", block.getType());
        dto.put("content", block.getContentJson());
        dto.put("sort_order", block.getSortOrder());
        dto.put("created_by", block.getCreatedBy().toString());
        dto.put("created_at", block.getCreatedAt().toString());
        dto.put("updated_at", block.getUpdatedAt().toString());
        return dto;
    }

    // ============================================================
    //  分类管理
    // ============================================================

    @GetMapping("/kb/{kbId}/categories")
    public Map<String, Object> listCategories(
            @PathVariable UUID kbId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_category",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        List<WikiCategoryEntity> tree = categoryService.tree(kbId);
        return Map.of("code", 0, "message", "success",
                "data", tree.stream().map(this::toCategoryDto).toList());
    }

    @PostMapping("/kb/{kbId}/categories")
    public ResponseEntity<Map<String, Object>> createCategory(
            @PathVariable UUID kbId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String name = (String) body.get("name");
        String slug = (String) body.get("slug");
        String parentIdStr = (String) body.get("parent_id");
        if (name == null || name.isBlank() || slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 和 slug 必填");
        }
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_category",
                com.nocobase.auth.AclPolicyEntity.Action.CREATE);
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        WikiCategoryEntity entity = categoryService.create(kbId, parentId, name, slug, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "CREATE", "wiki_category", entity.getId().toString(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toCategoryDto(entity)));
    }

    @PutMapping("/categories/{id}")
    public Map<String, Object> updateCategory(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_category",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        String name = (String) body.get("name");
        String slug = (String) body.get("slug");
        String parentIdStr = (String) body.get("parent_id");
        WikiCategoryEntity entity = categoryService.update(id, name, slug,
                parentIdStr != null ? UUID.fromString(parentIdStr) : null, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "UPDATE", "wiki_category", id.toString(), body);
        return Map.of("code", 0, "message", "success", "data", toCategoryDto(entity));
    }

    @DeleteMapping("/categories/{id}")
    public Map<String, Object> deleteCategory(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_category",
                com.nocobase.auth.AclPolicyEntity.Action.DELETE);
        WikiCategoryEntity entity = categoryService.get(id);
        if (!entity.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除");
        }
        categoryService.delete(id, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "DELETE", "wiki_category", id.toString(), null);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id.toString()));
    }

    // ============================================================
    //  全文搜索
    // ============================================================

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String q,
            @RequestParam(required = false) UUID kbId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        var result = searchService.search(q, kbId, user.tenantId(), page - 1, size);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(this::toPageDto).toList();
        return Map.of(
                "code", 0, "message", "success",
                "data", items,
                "total", result.getTotalElements(),
                "page", page,
                "size", size);
    }

    /**
     * 混合搜索：结合 FTS 和向量检索（AI 增强）。
     */
    @GetMapping("/search/hybrid")
    public Map<String, Object> hybridSearch(
            @RequestParam String q,
            @RequestParam(required = false) UUID kbId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        
        // 使用 WikiEmbeddingService.hybridSearch 进行混合检索
        var result = embeddingService.hybridSearch(q, kbId, user.tenantId(), page - 1, size);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(this::toPageDto).toList();
        return Map.of(
                "code", 0, "message", "success",
                "data", items,
                "total", result.getTotalElements(),
                "page", page,
                "size", size);
    }

    // ============================================================
    //  模板管理
    // ============================================================

    @GetMapping("/kb/{kbId}/templates")
    public Map<String, Object> listTemplates(
            @PathVariable UUID kbId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        var templates = templateService.listTemplates(kbId);
        List<Map<String, Object>> dtoList = templates.stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId().toString());
                    m.put("kb_id", t.getKbId().toString());
                    m.put("name", t.getName());
                    m.put("title", t.getTitle());
                    m.put("icon", t.getIcon());
                    m.put("blocks", t.getContentJson());
                    m.put("created_at", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
                    return m;
                }).toList();
        return Map.of(
                "code", 0, "message", "success",
                "data", dtoList,
                "total", dtoList.size());
    }

    @PostMapping("/pages/{id}/mark-template")
    public Map<String, Object> markTemplate(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Boolean isTemplate = (Boolean) body.get("isTemplate");
        if (isTemplate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isTemplate 必填");
        }
        pageService.markTemplate(id, isTemplate, user.tenantId());
        return Map.of("code", 0, "message", "success");
    }

    @PostMapping("/pages/{id}/from-template")
    public Map<String, Object> createFromTemplate(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID kbId = UUID.fromString(body.get("kbId").toString());
        UUID parentId = body.get("parentId") != null ? 
            UUID.fromString(body.get("parentId").toString()) : null;
        String title = (String) body.get("title");
        String slug = (String) body.get("slug");
        
        var newPage = templateService.createPageFromTemplate(
            id, kbId, parentId, title, slug, user.userId(), user.tenantId());
        
        eventPublisher.publishEvent(new RecordChangeEvent(
            RecordChangeEvent.ChangeType.CREATE, "wiki_page",
            newPage.getId().toString(), toPageDto(newPage), user.tenantId(), user.userId()));
        
        return Map.of(
                "code", 0, "message", "success",
                "data", toPageDto(newPage));
    }

    // ============================================================
    //  反向链接
    // ============================================================

    @GetMapping("/pages/{id}/backlinks")
    public Map<String, Object> listBacklinks(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        
        var page = pageService.get(id);
        var backlinks = backlinkRepository.findByTargetPageIdOrderByCreatedAtDesc(id);
        
        List<Map<String, Object>> dtoList = backlinks.stream()
                .map(bl -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", bl.getId().toString());
                    m.put("sourcePageId", bl.getSourcePageId().toString());
                    m.put("targetPageId", bl.getTargetPageId() != null ? 
                        bl.getTargetPageId().toString() : null);
                    m.put("targetSlug", bl.getTargetSlug());
                    m.put("createdAt", bl.getCreatedAt().toString());
                    
                    // 获取源页面信息
                    var sourcePage = pageService.get(bl.getSourcePageId());
                    if (sourcePage != null) {
                        m.put("sourceTitle", sourcePage.getTitle());
                        m.put("sourceSlug", sourcePage.getSlug());
                    }
                    return m;
                }).toList();
        
        return Map.of(
                "code", 0, "message", "success",
                "data", dtoList,
                "total", dtoList.size());
    }

    // ============================================================
    //  AI 助手端点
    // ============================================================

    @PostMapping("/pages/{pageId}/ask")
    public Map<String, Object> askQuestion(
            @PathVariable UUID pageId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        
        WikiPageEntity page = pageService.get(pageId);
        if (!page.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此页面");
        }
        
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 必填");
        }
        
        String context = (String) body.get("context");
        
        // RAG：先用混合检索（FTS + 向量）取回相关页面作为上下文
        var searchResults = embeddingService.hybridSearch(question, page.getKnowledgeBaseId(),
                user.tenantId(), 0, 5);
        
        StringBuilder retrieved = new StringBuilder();
        if (searchResults != null && !searchResults.isEmpty()) {
            for (var result : searchResults.getContent()) {
                retrieved.append("【").append(result.getTitle()).append("】\n");
                String c = result.getContent();
                retrieved.append(c == null ? "" : c.substring(0, Math.min(500, c.length()))).append("\n\n");
            }
        }
        String ragContext = (context == null ? "" : context + "\n\n") + retrieved;
        
        // 调用真 LLM（AiAssistantService → Python /api/ai/chat，带限流/缓存/配额 + 降级）
        Map<String, Object> aiResult = aiAssistantService.askQuestion(question, ragContext, auth);
        String answer = extractAiText(aiResult, "answer");
        
        // AI 不可用（未启用/调用失败）时回退到检索结果拼接，保证功能不劣化且诚实标注
        if (isUnavailable(answer)) {
            log.warn("[Wiki/ask] AI 服务不可用，回退到知识库检索结果");
            StringBuilder fallback = new StringBuilder();
            if (searchResults == null || searchResults.isEmpty()) {
                fallback.append("未找到相关答案。");
            } else {
                fallback.append("（AI 服务不可用，以下为知识库检索结果）\n\n");
                for (var result : searchResults.getContent()) {
                    fallback.append("【").append(result.getTitle()).append("】\n");
                    String c = result.getContent();
                    fallback.append(c == null ? "" : c.substring(0, Math.min(200, c.length()))).append("\n\n");
                }
            }
            answer = fallback.toString();
        }
        
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "ASK", "wiki_page", pageId.toString(), Map.of("question", question));
        
        return Map.of("code", 0, "message", "success", "data",
                Map.of("answer", answer));
    }

    @PostMapping("/pages/{pageId}/generate-outline")
    public Map<String, Object> generateOutline(
            @PathVariable UUID pageId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        
        WikiPageEntity page = pageService.get(pageId);
        if (!page.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此页面");
        }
        
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 必填");
        }
        
        // 调用真 LLM 生成大纲；不可用时回退为“提取 Markdown 标题级别”
        String outline;
        Map<String, Object> aiResult = aiAssistantService.chat(
                "请根据以下内容生成结构化的 Markdown 大纲（层级标题 + 要点），只输出大纲本身：\n\n" + content,
                null, 1024, auth);
        String aiText = extractAiText(aiResult, "result");
        outline = isUnavailable(aiText) ? extractOutline(content) : aiText;
        
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "GENERATE_OUTLINE", "wiki_page", pageId.toString(),
                Map.of("content_length", content.length()));
        
        return Map.of("code", 0, "message", "success", "data",
                Map.of("outline", outline));
    }

    @PostMapping("/ai/polish")
    public Map<String, Object> polishText(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text 必填");
        }
        
        // 调用真 LLM 润色；不可用时回退为空白归一化（明确降级，不再伪装成 AI 润色）
        Map<String, Object> aiResult = aiAssistantService.chat(
                "请润色以下文本，保持原意、修正语病并使其更通顺专业，只输出润色后的文本：\n\n" + text,
                null, 1024, auth);
        String aiText = extractAiText(aiResult, "result");
        String polished = isUnavailable(aiText) ? polishTextSimple(text) : aiText;
        
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "POLISH", "wiki_content", null,
                Map.of("original_length", text.length()));
        
        return Map.of("code", 0, "message", "success", "data",
                Map.of("polishedText", polished));
    }

    /**
     * 从 AiAssistantService 返回的统一信封 {code,message,data:{...}} 中取文本字段。
     */
    @SuppressWarnings("unchecked")
    private String extractAiText(Map<String, Object> aiResult, String field) {
        if (aiResult == null) return null;
        Object data = aiResult.get("data");
        if (data instanceof Map<?, ?> m) {
            Object v = m.get(field);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    /** 判断 AI 结果是否为“不可用”降级文案（AiAssistantService 内部降级时的提示）。 */
    private boolean isUnavailable(String text) {
        return text == null || text.isBlank() || text.contains("暂未启用") || text.contains("不可用");
    }

    private String extractOutline(String content) {
        StringBuilder outline = new StringBuilder();
        String[] lines = content.split("\n");
        int level = 1;
        
        for (String line : lines) {
            if (line.trim().startsWith("#")) {
                int headingLevel = countLeading(line, '#');
                level = Math.max(1, headingLevel);
                String title = line.replaceAll("^#+\\s*", "").trim();
                
                for (int i = 0; i < level - 1; i++) {
                    outline.append("  ");
                }
                outline.append(level).append(". ").append(title).append("\n");
            }
        }
        
        if (outline.length() == 0) {
            outline.append("1. 引言\n");
            outline.append("2. 主要内容\n");
            outline.append("3. 总结\n");
        }
        
        return outline.toString();
    }

    private int countLeading(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
            else break;
        }
        return count;
    }

    private String polishTextSimple(String text) {
        // 简化版润色：去除多余空格，规范化标点
        String polished = text.replaceAll("\\s+", " ").trim();
        polished = polished.replaceAll(" +", " ");
        return polished;
    }

    // ============================================================
    //  DTO 转换
    // ============================================================

    public record BatchUpsertRequest(UUID pageId, List<BlockData> blocks) {}
    public record BlockData(String type, Object content, String language, Boolean checked) {}

    private Map<String, Object> toKbDto(KnowledgeBaseEntity e) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", e.getId().toString());
        m.put("tenant_id", e.getTenantId());
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("slug", e.getSlug());
        m.put("icon", e.getIcon());
        m.put("sort_order", e.getSortOrder());
        m.put("created_by", e.getCreatedBy() != null ? e.getCreatedBy().toString() : null);
        m.put("created_at", e.getCreatedAt().toString());
        m.put("updated_at", e.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> toPageDto(WikiPageEntity e) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", e.getId().toString());
        m.put("tenant_id", e.getTenantId());
        m.put("knowledge_base_id", e.getKnowledgeBaseId().toString());
        m.put("parent_id", e.getParentId() != null ? e.getParentId().toString() : null);
        m.put("title", e.getTitle());
        m.put("slug", e.getSlug());
        m.put("content", e.getContent());
        m.put("content_html", e.getContentHtml());
        m.put("status", e.getStatus());
        m.put("version", e.getVersion());
        m.put("created_by", e.getCreatedBy() != null ? e.getCreatedBy().toString() : null);
        m.put("updated_by", e.getUpdatedBy() != null ? e.getUpdatedBy().toString() : null);
        m.put("created_at", e.getCreatedAt().toString());
        m.put("updated_at", e.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> toVersionDto(WikiVersionEntity e) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", e.getId().toString());
        m.put("wiki_page_id", e.getWikiPageId().toString());
        m.put("version", e.getVersion());
        m.put("content", e.getContent());
        m.put("summary", e.getSummary());
        m.put("created_by", e.getCreatedBy() != null ? e.getCreatedBy().toString() : null);
        m.put("created_at", e.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> toCategoryDto(WikiCategoryEntity e) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", e.getId().toString());
        m.put("tenant_id", e.getTenantId());
        m.put("knowledge_base_id", e.getKnowledgeBaseId().toString());
        m.put("parent_id", e.getParentId() != null ? e.getParentId().toString() : null);
        m.put("name", e.getName());
        m.put("slug", e.getSlug());
        m.put("sort_order", e.getSortOrder());
        m.put("created_at", e.getCreatedAt().toString());
        m.put("updated_at", e.getUpdatedAt().toString());
        return m;
    }
}