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
    private AiAssistantService aiAssistantService;
    private WikiController controller;

    /** AiAssistantService 内部降级文案（用于 stub“AI 不可用”以触发端点降级路径）。 */
    private static final String AI_UNAVAILABLE = "AI 助手暂未启用或不可用。请联系管理员配置 AI 服务。";

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
        aiAssistantService = mock(AiAssistantService.class);

        // 默认 stub：AI 不可用 → 端点走降级路径（既有断言验证的就是降级逻辑）
        when(aiAssistantService.askQuestion(anyString(), any(), any()))
                .thenReturn(Map.of("code", 0, "message", "AI 服务不可用",
                        "data", Map.of("answer", AI_UNAVAILABLE)));
        when(aiAssistantService.chat(anyString(), any(), anyInt(), any()))
                .thenReturn(Map.of("code", 0, "message", "AI 服务不可用",
                        "data", Map.of("result", AI_UNAVAILABLE)));

        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(eventPublisher).publishEvent(any());

        controller = new WikiController(kbService, pageService, categoryService,
                searchService, aclEnforcer, permissionService, attachmentService,
                wikiBlockService, auditService, eventPublisher, wikiTemplateService,
                wikiBacklinkRepository, wikiEmbeddingService, aiAssistantService);
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
                Map.of("question", "什么是 Java?", "context", "Java 是一种编程语言"),
                "Bearer test-token", testUser);

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
                () -> controller.askQuestion(page.getId(), Map.of(), null, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void askQuestion_wrongTenant_throws403() {
        WikiPageEntity page = makePage("other-tenant");
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.askQuestion(page.getId(),
                        Map.of("question", "q"), null, testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- POST /api/wiki/{pageId}/generate-outline ----

    @Test
    void generateOutline_returnsOutline() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);

        Map<String, Object> result = controller.generateOutline(page.getId(),
                Map.of("content", "# Introduction\n## Overview\nSome content"),
                "Bearer test-token", testUser);

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
                () -> controller.generateOutline(page.getId(), Map.of(), null, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void generateOutline_wrongTenant_throws403() {
        WikiPageEntity page = makePage("other-tenant");
        when(pageService.get(page.getId())).thenReturn(page);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.generateOutline(page.getId(),
                        Map.of("content", "x"), null, testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- POST /api/ai/polish ----

    @Test
    void polishText_returnsPolishedText() {
        Map<String, Object> result = controller.polishText(
                Map.of("text", "Hello   world  "), "Bearer test-token", testUser);

        assertEquals(0, result.get("code"));
        String polished = (String) ((Map<?, ?>) result.get("data")).get("polishedText");
        assertNotNull(polished);
        assertEquals("Hello world", polished);
    }

    @Test
    void polishText_missingText_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.polishText(Map.of(), null, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ============================================================
    //  AI 可用主路径（红线：必须证明真调用了 LLM，而不只是降级路径）
    // ============================================================

    @Test
    void askQuestion_aiAvailable_returnsLlmAnswer() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);
        when(wikiEmbeddingService.hybridSearch(anyString(), any(UUID.class), eq(tenantId), eq(0), eq(5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
        when(aiAssistantService.askQuestion(anyString(), any(), eq("Bearer test-token")))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("answer", "Java 是一种面向对象编程语言。")));

        Map<String, Object> result = controller.askQuestion(page.getId(),
                Map.of("question", "什么是 Java?"), "Bearer test-token", testUser);

        String answer = (String) ((Map<?, ?>) result.get("data")).get("answer");
        assertEquals("Java 是一种面向对象编程语言。", answer);
        // 证明真走了 LLM（而非回退检索拼接）
        verify(aiAssistantService).askQuestion(eq("什么是 Java?"), any(), eq("Bearer test-token"));
    }

    @Test
    void generateOutline_aiAvailable_returnsLlmOutline() {
        WikiPageEntity page = makePage(tenantId);
        when(pageService.get(page.getId())).thenReturn(page);
        when(aiAssistantService.chat(anyString(), any(), anyInt(), eq("Bearer test-token")))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("result", "# 大纲\n- 要点一")));

        Map<String, Object> result = controller.generateOutline(page.getId(),
                Map.of("content", "一些内容"), "Bearer test-token", testUser);

        String outline = (String) ((Map<?, ?>) result.get("data")).get("outline");
        assertEquals("# 大纲\n- 要点一", outline);
        verify(aiAssistantService).chat(anyString(), any(), anyInt(), eq("Bearer test-token"));
    }

    @Test
    void polishText_aiAvailable_returnsLlmPolished() {
        when(aiAssistantService.chat(anyString(), any(), anyInt(), eq("Bearer test-token")))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("result", "这是一段经过润色的专业文本。")));

        Map<String, Object> result = controller.polishText(
                Map.of("text", "这个句子不太好"), "Bearer test-token", testUser);

        String polished = (String) ((Map<?, ?>) result.get("data")).get("polishedText");
        assertEquals("这是一段经过润色的专业文本。", polished);
        assertFalse(polished.equals("这个句子不太好"), "AI 可用时应返回 LLM 润色结果而非原文");
        verify(aiAssistantService).chat(anyString(), any(), anyInt(), eq("Bearer test-token"));
    }
}