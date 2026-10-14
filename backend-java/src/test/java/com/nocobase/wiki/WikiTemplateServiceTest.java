package com.nocobase.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PHASE105: WikiTemplateService 测试（含租户隔离分支）。 */
class WikiTemplateServiceTest {

    private WikiTemplateRepository templateRepository;
    private KnowledgeBaseService knowledgeBaseService;
    private WikiPageService pageService;
    private WikiBlockService blockService;
    private WikiTemplateService service;

    private static final String TENANT = "t1";
    private static final String OTHER_TENANT = "t2";

    @BeforeEach
    void setUp() {
        templateRepository = mock(WikiTemplateRepository.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        pageService = mock(WikiPageService.class);
        blockService = mock(WikiBlockService.class);
        service = new WikiTemplateService(templateRepository, knowledgeBaseService, pageService, blockService);
        when(templateRepository.save(any(WikiTemplateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private WikiTemplateEntity tplOf(UUID id, String tenantId, String contentJson) {
        WikiTemplateEntity e = new WikiTemplateEntity();
        e.setId(id);
        e.setKbId(UUID.randomUUID());
        e.setName("n");
        e.setTitle("t");
        e.setContentJson(contentJson);
        e.setTenantId(tenantId);
        return e;
    }

    @Test
    void createTemplate_persistsAndReturns() {
        UUID kbId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();

        WikiTemplateEntity out = service.createTemplate(
                kbId, "n", "标题", List.of(Map.of("type", "text")), "icon", TENANT, createdBy);

        assertThat(out.getKbId()).isEqualTo(kbId);
        assertThat(out.getName()).isEqualTo("n");
        assertThat(out.getTitle()).isEqualTo("标题");
        assertThat(out.getIcon()).isEqualTo("icon");
        assertThat(out.getTenantId()).isEqualTo(TENANT);
        assertThat(out.getCreatedBy()).isEqualTo(createdBy);
        assertThat(out.getCreatedAt()).isNotNull();
        assertThat(out.getUpdatedAt()).isNotNull();
        // 验证知识库归属被校验（租户隔离入口）
        verify(knowledgeBaseService).get(kbId, TENANT);
    }

    @Test
    void createTemplate_nullBlocks_defaultsToEmptyArray() {
        WikiTemplateEntity out = service.createTemplate(
                UUID.randomUUID(), "n", "t", null, null, TENANT, UUID.randomUUID());

        assertThat(out.getContentJson()).isEqualTo("[]");
    }

    @Test
    void createTemplate_kbNotInTenant_propagates() {
        UUID kbId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在"))
                .when(knowledgeBaseService).get(kbId, TENANT);

        assertThatThrownBy(() -> service.createTemplate(
                kbId, "n", "t", null, null, TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listTemplates_delegatesWithTenantFilter() {
        List<WikiTemplateEntity> expected = List.of(tplOf(UUID.randomUUID(), TENANT, "[]"));
        UUID kbId = UUID.randomUUID();
        when(templateRepository.findByKbIdAndTenantIdOrderByUpdatedAtDesc(kbId, TENANT))
                .thenReturn(expected);

        assertThat(service.listTemplates(kbId, TENANT)).isEqualTo(expected);
    }

    @Test
    void getTemplate_found_returns() {
        UUID id = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(id, TENANT, "[]");
        when(templateRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(tpl));

        assertThat(service.getTemplate(id, TENANT)).isSameAs(tpl);
    }

    @Test
    void getTemplate_crossTenant_returns404() {
        // repository 按 (id, tenantId) 过滤，跨租户查不到 → Optional.empty → 404
        UUID id = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantId(id, OTHER_TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTemplate(id, OTHER_TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteTemplate_success_deletes() {
        UUID id = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(id, TENANT, "[]");
        when(templateRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(tpl));

        service.deleteTemplate(id, TENANT);

        verify(templateRepository).delete(tpl);
    }

    @Test
    void deleteTemplate_tenantMismatch_throws403() {
        // repository 被 mock 成返回「租户不一致」的实体（模拟越权路径）→ 403
        UUID id = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(id, OTHER_TENANT, "[]");
        when(templateRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.deleteTemplate(id, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(templateRepository, never()).delete(any());
    }

    @Test
    void createPageFromTemplate_success_copiesBlocks() {
        UUID templateId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(templateId, TENANT, "[{\"type\":\"text\"}]");
        when(templateRepository.findByIdAndTenantId(templateId, TENANT)).thenReturn(Optional.of(tpl));

        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        when(pageService.create(eq(kbId), any(), eq("标题"), eq("slug"), eq(""), eq(createdBy), eq(TENANT)))
                .thenReturn(page);

        WikiPageEntity out = service.createPageFromTemplate(
                templateId, kbId, null, "标题", "slug", createdBy, TENANT);

        assertThat(out).isSameAs(page);
        verify(blockService).createBlocksForPage(eq(page.getId()), any(), eq(TENANT), eq(createdBy));
    }

    @Test
    void createPageFromTemplate_emptyContentJson_skipsBlocks() {
        UUID templateId = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(templateId, TENANT, "");
        when(templateRepository.findByIdAndTenantId(templateId, TENANT)).thenReturn(Optional.of(tpl));

        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        when(pageService.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        service.createPageFromTemplate(templateId, UUID.randomUUID(), null, "t", "s", UUID.randomUUID(), TENANT);

        verify(blockService, never()).createBlocksForPage(any(), any(), any(), any());
    }

    @Test
    void createPageFromTemplate_tenantMismatch_throws403() {
        UUID templateId = UUID.randomUUID();
        WikiTemplateEntity tpl = tplOf(templateId, OTHER_TENANT, "[]");
        when(templateRepository.findByIdAndTenantId(templateId, TENANT)).thenReturn(Optional.of(tpl));

        assertThatThrownBy(() -> service.createPageFromTemplate(
                templateId, UUID.randomUUID(), null, "t", "s", UUID.randomUUID(), TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
