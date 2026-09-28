package com.nocobase.wiki;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ai.AiAssistantService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import com.nocobase.event.RecordChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WikiController <b>HTTP 层契约测试</b>（PHASE 57 收尾）。
 *
 * <p>存在意义：既有的 {@link WikiControllerAiEndpointsTest} 是<b>直接调用 controller 方法</b>，
 * 因此无法发现 URL 映射错误 —— 上一轮正是因此漏掉了"前端调 /api/wiki/{id}/ask，
 * 而后端实际是 /api/wiki/pages/{id}/ask"的 404 问题。
 *
 * <p>本测试通过 MockMvc 走真实 HTTP 路径映射，并额外断言<b>旧错误路径返回 404</b>，
 * 防止后续再次误用旧 URL。
 */
@WebMvcTest(WikiController.class)
@AutoConfigureMockMvc(addFilters = false)
class WikiControllerHttpContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private KnowledgeBaseService kbService;
    @MockBean private WikiPageService pageService;
    @MockBean private WikiCategoryService categoryService;
    @MockBean private WikiSearchService searchService;
    @MockBean private AclEnforcer aclEnforcer;
    @MockBean private WikiPermissionService permissionService;
    @MockBean private WikiAttachmentService attachmentService;
    @MockBean private WikiBlockService blockService;
    @MockBean private AuditService auditService;
    @MockBean private ApplicationEventPublisher eventPublisher;
    @MockBean private WikiTemplateService templateService;
    @MockBean private WikiBacklinkRepository backlinkRepository;
    @MockBean private WikiEmbeddingService embeddingService;
    @MockBean private AiAssistantService aiAssistantService;
    // 照抄既有 MockMvc 测试模式：mock 安全相关 bean，配合 addFilters=false
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;

    private static final String TENANT = "test-tenant";
    private static final UUID PAGE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void login() {
        AuthenticatedUser u = new AuthenticatedUser(UUID.randomUUID(), "alice", TENANT);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private WikiPageEntity makePage() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(PAGE_ID);
        page.setTenantId(TENANT);
        page.setKnowledgeBaseId(UUID.randomUUID());
        page.setTitle("Test Page");
        page.setSlug("test-page");
        page.setContent("# Title\nSome content");
        page.setStatus("PUBLISHED");
        page.setVersion(1);
        page.setCreatedAt(Instant.now());
        page.setUpdatedAt(Instant.now());
        return page;
    }

    @BeforeEach
    void stubCommon() {
        when(pageService.get(PAGE_ID)).thenReturn(makePage());
        when(embeddingService.hybridSearch(anyString(), any(), eq(TENANT), eq(0), eq(5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
    }

    // ============================================================
    //  POST /api/wiki/pages/{pageId}/ask
    // ============================================================

    @Test
    void ask_realHttpPath_returns200() throws Exception {
        when(aiAssistantService.askQuestion(anyString(), any(), any()))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("answer", "Java 是一种编程语言。")));

        mockMvc.perform(post("/api/wiki/pages/{pageId}/ask", PAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"什么是 Java?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("Java 是一种编程语言。"));
    }

    /** 旧路径（前端曾误用，缺 pages 段）必须 404 —— 防止回归。 */
    @Test
    void ask_legacyPathWithoutPages_returns404() throws Exception {
        mockMvc.perform(post("/api/wiki/{pageId}/ask", PAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    //  POST /api/wiki/pages/{pageId}/generate-outline
    // ============================================================

    @Test
    void generateOutline_realHttpPath_returns200() throws Exception {
        when(aiAssistantService.chat(anyString(), any(), anyInt(), any()))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("result", "# 大纲\n- 要点")));

        mockMvc.perform(post("/api/wiki/pages/{pageId}/generate-outline", PAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# Intro\\ntext\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.outline").value("# 大纲\n- 要点"));
    }

    // ============================================================
    //  POST /api/wiki/ai/polish
    // ============================================================

    @Test
    void polish_realHttpPath_returns200() throws Exception {
        when(aiAssistantService.chat(anyString(), any(), anyInt(), any()))
                .thenReturn(Map.of("code", 0, "message", "success",
                        "data", Map.of("result", "润色后的文本")));

        mockMvc.perform(post("/api/wiki/ai/polish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"原文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.polishedText").value("润色后的文本"));
    }

    /** 旧路径 /api/ai/polish（不在 /api/wiki 下）必须 404 —— 防止回归。 */
    @Test
    void polish_legacyPathOutsideWiki_returns404() throws Exception {
        mockMvc.perform(post("/api/ai/polish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
