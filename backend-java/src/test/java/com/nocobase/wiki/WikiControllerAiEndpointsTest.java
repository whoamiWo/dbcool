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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * WikiController AI 端点测试（PHASE 57 §4）。
 *
 * <p>验证三个 AI 端点真实存在、带租户/权限校验、参数非法→400。
 * 采用与 WikiControllerTest 相同的纯单元测试模式（直接构造 controller + mock 依赖）。
 */
class WikiControllerAiEndpointsTest {

    private KnowledgeBaseService kbService;
    private WikiPageService pageService;
    private WikiCategoryService categoryService;
    private WikiSearchService searchService;
    private AclEnforcer aclEnforcer;
    private WikiPermissionService permissionService;
    private WikiAttachmentService attachmentService;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private WikiBlockService wikiBlockService;
    private WikiTemplateService wikiTemplateService;
    private WikiBacklinkRepository wikiBacklinkRepository;
    private WikiEmbeddingService wikiEmbeddingService;
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
        wikiBlockService = mock(WikiBlockService.class);
        wikiTemplateService = mock(WikiTemplateService.class);
        wikiBacklinkRepository = mock(WikiBacklinkRepository.class);
        wikiEmbeddingService = mock(WikiEmbeddingService.class);

        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(eventPublisher).publishEvent(any());

        controller = new WikiController(kbService, pageService, categoryService,
                searchService, aclEnforcer, permissionService, attachmentService,
                wikiBlockService, auditService, eventPublisher, wikiTemplateService,
                wikiBacklinkRepository, wikiEmbeddingService);
    }

    private WikiPageEntity makePage(String tenant) {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTenantId(tenant);
        page.setKnowledgeBaseId(UUID.randomUUID());
        page.setTitle("Test Page");
        page.setSlug("test-page");
        page.setContent("# Title\n## Section\nSome content here");
        page.setStatus("PUBLISHED");
        page.setVersion(1);
        page.setCreatedAt(Instant.now());
        page.setUpdatedAt(Instant.now());
        return page;
    }

    // ---- POST /api/wiki/{pageId}/ask ----

    @Test
    void askQuestion_returnsAnswer() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);
        when(wikiEmbeddingService.hybridSearch(anyString(), any(UUID.class), eq(tenantId), eq(0), eq(5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));

        Map<String, Object> result = controller.askQuestion(page.getId(),
                Map.of("question", "什么是 Java?", "context", "Java 是一种编程语言"), testUser);

        assertEquals(0, result.get("code"));
        assertNotNull(((Map<?, ?>) result.get("data")).get("answer"));
        verify(auditService).log(eq(tenantId), eq(userId), eq("alice"),
                eq("ASK"), eq("wiki_page"), eq(page.getId().toString()), any());
    }

    @Test
    void askQuestion_missingQuestion_throws400() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.askQuestion(page.getId(), Map.of(), testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void askQuestion_wrongTenant_throws403() {
        WikiPageEntity page = makePage("other-tenant");
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.askQuestion(page.getId(),
                        Map.of("question", "q"), testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- POST /api/wiki/{pageId}/generate-outline ----

    @Test
    void generateOutline_returnsOutline() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);

        Map<String, Object> result = controller.generateOutline(page.getId(),
                Map.of("content", "# Introduction\n## Overview\nSome content"), testUser);

        assertEquals(0, result.get("code"));
        String outline = (String) ((Map<?, ?>) result.get("data")).get("outline");
        assertNotNull(outline);
        assertTrue(outline.contains("Introduction"));
    }

    @Test
    void generateOutline_missingContent_throws400() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.generateOutline(page.getId(), Map.of(), testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void generateOutline_wrongTenant_throws403() {
        WikiPageEntity page = makePage("other-tenant");
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.generateOutline(page.getId(),
                        Map.of("content", "x"), testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- POST /api/ai/polish ----

    @Test
    void polishText_returnsPolishedText() {
        Map<String, Object> result = controller.polishText(
                Map.of("text", "Hello   world  "), testUser);

        assertEquals(0, result.get("code"));
        String polished = (String) ((Map<?, ?>) result.get("data")).get("polishedText");
        assertNotNull(polished);
        assertEquals("Hello world", polished);
    }

    @Test
    void polishText_missingText_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.polishText(Map.of(), testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}