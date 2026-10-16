package com.nocobase.wiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nocobase.ai.AiAssistantService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

class WikiControllerTest {

    private KnowledgeBaseService kbService;
    private WikiPageService pageService;
    private WikiCategoryService categoryService;
    private WikiSearchService searchService;
    private AclEnforcer aclEnforcer;
    private WikiPermissionService permissionService;
    private WikiAttachmentService attachmentService;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private WikiBlockService blockService;
    private WikiTemplateService templateService;
    private WikiBacklinkRepository backlinkRepository;
    private WikiEmbeddingService embeddingService;
    private AiAssistantService aiAssistantService;
    private WikiController controller;

    private AuthenticatedUser testUser;
    private UUID userId;
    private String tenantId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = "test-tenant";
        testUser = new AuthenticatedUser(userId, "alice", tenantId);
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));

        kbService = mock(KnowledgeBaseService.class);
        pageService = mock(WikiPageService.class);
        categoryService = mock(WikiCategoryService.class);
        searchService = mock(WikiSearchService.class);
        aclEnforcer = mock(AclEnforcer.class);
        permissionService = mock(WikiPermissionService.class);
        attachmentService = mock(WikiAttachmentService.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        blockService = mock(WikiBlockService.class);
        templateService = mock(WikiTemplateService.class);
        backlinkRepository = mock(WikiBacklinkRepository.class);
        embeddingService = mock(WikiEmbeddingService.class);
        aiAssistantService = mock(AiAssistantService.class);

        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(eventPublisher).publishEvent(any());
        doNothing().when(permissionService)
                .assertPagePermission(any(), anyString(), any(), any());
        doNothing().when(permissionService)
                .assertKbPermission(any(), anyString(), any(), any());

        controller = new WikiController(kbService, pageService, categoryService,
                searchService, aclEnforcer, permissionService, attachmentService,
                blockService, auditService, eventPublisher, templateService,
                backlinkRepository, embeddingService, aiAssistantService);
    }

    // ---- 知识库 CRUD ----

    @Test
    void createKb_success() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        kb.setName("KB");
        kb.setSlug("kb-slug");
        kb.setCreatedAt(Instant.now());
        kb.setUpdatedAt(Instant.now());
        when(kbService.create(anyString(), anyString(), anyString(), anyString(), eq(userId), eq(tenantId)))
                .thenReturn(kb);

        Map<String, Object> body = Map.of("name", "KB", "slug", "kb-slug", "description", "D", "icon", "b");
        ResponseEntity<Map<String, Object>> resp = controller.createKb(body, testUser);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Map<String, Object> data = resp.getBody();
        assertNotNull(data);
        assertEquals(0, data.get("code"));
        assertEquals("success", data.get("message"));
        assertTrue(data.containsKey("data"));
        verify(auditService, times(1)).log(eq(tenantId), eq(userId), eq("alice"),
                eq("CREATE"), eq("knowledge_base"), any(), any());
    }

    @Test
    void createKb_missingName_throws() {
        Map<String, Object> body = Map.of("slug", "kb-slug");
        assertThrows(ResponseStatusException.class, () -> controller.createKb(body, testUser));
    }

    @Test
    void listKb_returnsEnvelope() {
        when(kbService.list(eq(tenantId))).thenReturn(List.of());
        Map<String, Object> result = controller.listKb(testUser);
        assertEquals(0, result.get("code"));
        assertEquals("success", result.get("message"));
        assertTrue(result.get("data") instanceof List);
    }

    @Test
    void getKb_success() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        kb.setName("KB");
        kb.setTenantId(tenantId);
        kb.setCreatedAt(Instant.now());
        kb.setUpdatedAt(Instant.now());
        when(kbService.get(any(), any())).thenReturn(kb);
        Map<String, Object> result = controller.getKb(kb.getId(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void getKb_wrongTenant_throws403() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        kb.setTenantId("other-tenant");
        when(kbService.get(any(), any())).thenReturn(kb);
        assertThrows(ResponseStatusException.class, () -> controller.getKb(kb.getId(), testUser));
    }

    // ---- 文档 CRUD ----

    @Test
    void createPage_success() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTitle("Page Title");
        page.setSlug("page-slug");
        page.setStatus("DRAFT");
        page.setVersion(1);
        page.setTenantId(tenantId);
        page.setKnowledgeBaseId(UUID.randomUUID());
        page.setCreatedAt(Instant.now());
        page.setUpdatedAt(Instant.now());
        when(pageService.create(any(), isNull(), eq("Page Title"), eq("page-slug"), eq("Hello world"), eq(userId), eq(tenantId)))
                .thenReturn(page);

        Map<String, Object> body = Map.of(
                "knowledge_base_id", UUID.randomUUID().toString(),
                "slug", "page-slug", "title", "Page Title", "content", "Hello world");
        ResponseEntity<Map<String, Object>> resp = controller.createPage(body, testUser);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("code"));
    }

    @Test
    void listPages_success() {
        when(pageService.listByKbAndStatus(any(), isNull(), eq(tenantId))).thenReturn(List.of());
        Map<String, Object> result = controller.listPages(UUID.randomUUID(), null, 1, 10, testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 分类 CRUD ----

    @Test
    void createCategory_success() {
        WikiCategoryEntity cat = new WikiCategoryEntity();
        cat.setId(UUID.randomUUID());
        cat.setName("Cat");
        cat.setSlug("cat-slug");
        cat.setKnowledgeBaseId(UUID.randomUUID());
        cat.setTenantId(tenantId);
        cat.setCreatedAt(Instant.now());
        cat.setUpdatedAt(Instant.now());
        when(categoryService.create(any(), isNull(), eq("Cat"), eq("cat-slug"), eq(tenantId)))
                .thenReturn(cat);
        Map<String, Object> body = Map.of("name", "Cat", "slug", "cat-slug");
        ResponseEntity<Map<String, Object>> resp = controller.createCategory(UUID.randomUUID(), body, testUser);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("code"));
    }

    @Test
    void listCategories_success() {
        when(categoryService.tree(any(), any())).thenReturn(List.of());
        Map<String, Object> result = controller.listCategories(UUID.randomUUID(), testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 搜索 ----

    @Test
    void searchSuccess() {
        when(searchService.search(eq("test"), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        Map<String, Object> result = controller.search("test", UUID.randomUUID(), 0, 10, testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 版本 ----

    @Test
    void listVersionsSuccess() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTenantId(tenantId);
        when(pageService.get(any())).thenReturn(page);
        when(pageService.listVersions(any())).thenReturn(List.of());
        Map<String, Object> result = controller.listVersions(UUID.randomUUID(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void listVersions_wrongTenant_throws403() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTenantId("other-tenant");
        when(pageService.get(any())).thenReturn(page);
        assertThrows(ResponseStatusException.class, () -> controller.listVersions(UUID.randomUUID(), testUser));
    }

    @Test
    void restoreVersionSuccess() {
        WikiPageEntity restored = new WikiPageEntity();
        restored.setId(UUID.randomUUID());
        restored.setTenantId(tenantId);
        restored.setKnowledgeBaseId(UUID.randomUUID());
        restored.setStatus("DRAFT");
        restored.setVersion(1);
        restored.setCreatedAt(Instant.now());
        restored.setUpdatedAt(Instant.now());
        when(pageService.restoreVersion(any(), eq(2), eq(userId), eq(tenantId))).thenReturn(restored);
        Map<String, Object> result = controller.restoreVersion(UUID.randomUUID(), 2, testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 未覆盖的文档端点 ----

    /** 构造字段完整的 WikiPageEntity（toPageDto 需要 kbId/时间戳非空）。 */
    private WikiPageEntity fullPage() {
        WikiPageEntity p = new WikiPageEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setTitle("Page");
        p.setSlug("page-slug");
        p.setStatus("DRAFT");
        p.setVersion(1);
        p.setKnowledgeBaseId(UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    @Test
    void getPage_success() {
        WikiPageEntity page = fullPage();
        UUID id = page.getId();
        when(pageService.get(eq(id), eq(tenantId))).thenReturn(page);
        Map<String, Object> result = controller.getPage(id, testUser);
        assertEquals(0, result.get("code"));
        verify(aclEnforcer).assertCan(eq(userId), eq(tenantId), eq("wiki_page"), any());
    }

    @Test
    void getPageBySlug_success() {
        WikiPageEntity page = fullPage();
        when(pageService.getBySlug("my-slug", tenantId)).thenReturn(page);
        Map<String, Object> result = controller.getPageBySlug("my-slug", testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void updatePage_success() {
        WikiPageEntity updated = fullPage();
        updated.setTitle("Updated");
        when(pageService.update(any(), eq("Updated"), isNull(), isNull(), eq(userId), eq(tenantId)))
                .thenReturn(updated);
        Map<String, Object> result = controller.updatePage(updated.getId(), Map.of("title", "Updated"), testUser);
        assertEquals(0, result.get("code"));
        verify(auditService).log(eq(tenantId), eq(userId), eq("alice"), eq("UPDATE"), eq("wiki_page"), anyString(), any());
    }

    @Test
    void deletePage_wrongTenant_throws403() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTenantId("other-tenant");
        when(pageService.get(any())).thenReturn(page);
        assertThrows(ResponseStatusException.class, () -> controller.deletePage(page.getId(), testUser));
    }

    @Test
    void publishPage_success() {
        WikiPageEntity published = fullPage();
        published.setStatus("PUBLISHED");
        when(pageService.publish(any(), eq(userId), eq(tenantId))).thenReturn(published);
        Map<String, Object> result = controller.publishPage(published.getId(), testUser);
        assertEquals(0, result.get("code"));
        assertEquals("published", result.get("message"));
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(com.nocobase.event.RecordChangeEvent.class));
    }

    @Test
    void archivePage_success() {
        WikiPageEntity archived = fullPage();
        archived.setStatus("ARCHIVED");
        when(pageService.archive(any(), eq(userId), eq(tenantId))).thenReturn(archived);
        Map<String, Object> result = controller.archivePage(archived.getId(), testUser);
        assertEquals(0, result.get("code"));
        assertEquals("archived", result.get("message"));
    }

    @Test
    void getVersion_success() {
        WikiPageEntity page = fullPage();
        when(pageService.get(any())).thenReturn(page);
        var version = new com.nocobase.wiki.WikiVersionEntity();
        version.setId(UUID.randomUUID());
        version.setWikiPageId(page.getId());
        version.setContent("{}");
        version.setVersion(3);
        version.setCreatedBy(userId);
        version.setCreatedAt(Instant.now());
        when(pageService.getVersion(any(), eq(3))).thenReturn(version);
        Map<String, Object> result = controller.getVersion(page.getId(), 3, testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 块管理端点 ----

    @Test
    void listBlocks_success() {
        when(blockService.getBlocksByPageId(any())).thenReturn(List.of());
        Map<String, Object> result = controller.listBlocks(UUID.randomUUID(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void createBlocks_success() {
        when(blockService.createBlocksForPage(any(), any(), eq(tenantId), eq(userId)))
                .thenReturn(List.of());
        Map<String, Object> result = controller.createBlocks(UUID.randomUUID(), List.of(Map.of("type", "text")), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void updateBlock_success() {
        WikiBlockEntity block = new WikiBlockEntity();
        block.setId(UUID.randomUUID());
        block.setPageId(UUID.randomUUID());
        block.setType("text");
        block.setContentJson("{}");
        block.setSortOrder(1);
        block.setCreatedBy(userId);
        block.setCreatedAt(Instant.now());
        block.setUpdatedAt(Instant.now());
        when(blockService.moveBlock(any(), isNull(), isNull(), eq(tenantId))).thenReturn(block);
        Map<String, Object> result = controller.updateBlock(block.getId(), Map.of(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void deleteBlock_success() {
        doNothing().when(blockService).deleteBlocksByPageId(any(), eq(tenantId));
        Map<String, Object> result = controller.deleteBlock(UUID.randomUUID(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void reorderBlocks_success() {
        when(blockService.reorderBlocks(any(), any(), eq(tenantId))).thenReturn(List.of());
        Map<String, Object> result = controller.reorderBlocks(UUID.randomUUID(), List.of(), testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 知识库/分类 CRUD ----

    @Test
    void updateKb_success() {
        KnowledgeBaseEntity updated = new KnowledgeBaseEntity();
        updated.setId(UUID.randomUUID());
        updated.setTenantId(tenantId);
        updated.setName("New Name");
        updated.setSlug("new-name");
        updated.setCreatedAt(Instant.now());
        updated.setUpdatedAt(Instant.now());
        when(kbService.update(any(), eq("New Name"), isNull(), isNull(), isNull(), eq(tenantId)))
                .thenReturn(updated);
        Map<String, Object> result = controller.updateKb(updated.getId(), Map.of("name", "New Name"), testUser);
        assertEquals(0, result.get("code"));
        verify(auditService).log(eq(tenantId), eq(userId), eq("alice"), eq("UPDATE"), eq("knowledge_base"), anyString(), any());
    }

    @Test
    void deleteKb_wrongTenant_throws403() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        kb.setTenantId("other-tenant");
        when(kbService.get(any(), eq(tenantId))).thenReturn(kb);
        assertThrows(ResponseStatusException.class, () -> controller.deleteKb(kb.getId(), testUser));
    }

    @Test
    void updateCategory_success() {
        WikiCategoryEntity updated = new WikiCategoryEntity();
        updated.setId(UUID.randomUUID());
        updated.setTenantId(tenantId);
        updated.setName("New Cat");
        updated.setSlug("new-cat");
        updated.setKnowledgeBaseId(UUID.randomUUID());
        updated.setCreatedAt(Instant.now());
        updated.setUpdatedAt(Instant.now());
        when(categoryService.update(any(), eq("New Cat"), isNull(), isNull(), eq(tenantId)))
                .thenReturn(updated);
        Map<String, Object> result = controller.updateCategory(updated.getId(), Map.of("name", "New Cat"), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void deleteCategory_wrongTenant_throws403() {
        WikiCategoryEntity cat = new WikiCategoryEntity();
        cat.setId(UUID.randomUUID());
        cat.setTenantId("other-tenant");
        when(categoryService.get(any(), eq(tenantId))).thenReturn(cat);
        assertThrows(ResponseStatusException.class, () -> controller.deleteCategory(cat.getId(), testUser));
    }

    // ---- 搜索 / 混合搜索 ----

    @Test
    void hybridSearch_success() {
        when(embeddingService.hybridSearch(eq("query"), isNull(), eq(tenantId), eq(0), eq(10)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));
        Map<String, Object> result = controller.hybridSearch("query", null, 1, 10, testUser);
        assertEquals(0, result.get("code"));
    }

    // ---- 模板 ----

    @Test
    void markTemplate_success() {
        when(pageService.markTemplate(any(), eq(true), eq(tenantId))).thenReturn(fullPage());
        Map<String, Object> result = controller.markTemplate(UUID.randomUUID(), Map.of("isTemplate", true), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void markTemplate_missingIsTemplate_throws400() {
        assertThrows(ResponseStatusException.class, () -> controller.markTemplate(UUID.randomUUID(), Map.of(), testUser));
    }

    @Test
    void createFromTemplate_success() {
        WikiPageEntity created = fullPage();
        when(templateService.createPageFromTemplate(any(), any(), isNull(), eq("T"), eq("s"), eq(userId), eq(tenantId)))
                .thenReturn(created);
        Map<String, Object> body = Map.of("kbId", UUID.randomUUID().toString(), "title", "T", "slug", "s");
        Map<String, Object> result = controller.createFromTemplate(UUID.randomUUID(), body, testUser);
        assertEquals(0, result.get("code"));
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(com.nocobase.event.RecordChangeEvent.class));
    }

    // ---- 反向链接 ----

    @Test
    void listBacklinks_success() {
        var backlink = new com.nocobase.wiki.WikiBacklinkEntity();
        backlink.setId(UUID.randomUUID());
        backlink.setSourcePageId(UUID.randomUUID());
        backlink.setTargetPageId(UUID.randomUUID());
        backlink.setCreatedAt(java.time.Instant.now());
        when(pageService.get(any())).thenReturn(new WikiPageEntity());
        when(backlinkRepository.findByTargetPageIdOrderByCreatedAtDesc(any())).thenReturn(List.of(backlink));
        Map<String, Object> result = controller.listBacklinks(UUID.randomUUID(), testUser);
        assertEquals(0, result.get("code"));
    }
}