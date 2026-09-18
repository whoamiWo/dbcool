package com.nocobase.wiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(eventPublisher).publishEvent(any());
        doNothing().when(permissionService)
                .assertPagePermission(any(), anyString(), any(), any());
        doNothing().when(permissionService)
                .assertKbPermission(any(), anyString(), any(), any());

        controller = new WikiController(kbService, pageService, categoryService,
                searchService, aclEnforcer, permissionService, attachmentService,
                auditService, eventPublisher);
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
        when(kbService.get(any())).thenReturn(kb);
        Map<String, Object> result = controller.getKb(kb.getId(), testUser);
        assertEquals(0, result.get("code"));
    }

    @Test
    void getKb_wrongTenant_throws403() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        kb.setTenantId("other-tenant");
        when(kbService.get(any())).thenReturn(kb);
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
        when(categoryService.tree(any())).thenReturn(List.of());
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
}