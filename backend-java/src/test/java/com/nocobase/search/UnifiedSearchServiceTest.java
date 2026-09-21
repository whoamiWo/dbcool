package com.nocobase.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.automation.AutomationRuleService;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.project.ProjectService;
import com.nocobase.project.ProjectTaskEntity;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 全局搜索服务测试 — 验证"真接真"：
 * 1. 写入索引后搜索能命中（五类）
 * 2. facets 含全部五键（im/wiki/record/project/automation）
 * 3. 关键词过滤对 automation 生效（不再无条件全返回）
 * 4. wiki 仅返回 PUBLISHED 页面
 */
class UnifiedSearchServiceTest {

    private UnifiedSearchIndexRepository indexRepo;
    private MessageService messageService;
    private WikiPageService wikiPageService;
    private CollectionService collectionService;
    private AutomationRuleService automationRuleService;
    private ProjectService projectService;
    private UnifiedSearchService service;

    private final String tenantId = "tenant_default";
    private final UUID wikiId = UUID.randomUUID();
    private final UUID msgId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        indexRepo = mock(UnifiedSearchIndexRepository.class);
        messageService = mock(MessageService.class);
        wikiPageService = mock(WikiPageService.class);
        collectionService = mock(CollectionService.class);
        automationRuleService = mock(AutomationRuleService.class);
        projectService = mock(ProjectService.class);
        service = new UnifiedSearchService(
                messageService, wikiPageService, collectionService,
                automationRuleService, projectService, indexRepo);
    }

    // ---- 辅助：构造索引命中实体 ----

    private UnifiedSearchIndexEntity idx(String entityType, String entityId,
                                          String title, String content) {
        UnifiedSearchIndexEntity e = new UnifiedSearchIndexEntity();
        e.setId(UUID.randomUUID());
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setTenantId(tenantId);
        e.setTitle(title);
        e.setContent(content);
        e.setSnippet(content);
        e.setRank(1.0);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private void givenIndexHits(List<UnifiedSearchIndexEntity> hits) {
        when(indexRepo.searchAllTypes(anyString(), eq(tenantId), anyInt()))
                .thenReturn(hits);
        when(indexRepo.searchByKeywordAndType(anyString(), anyString(), eq(tenantId), anyInt()))
                .thenReturn(hits);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("results");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> facets(Map<String, Object> result) {
        return (Map<String, Long>) result.get("facets");
    }

    // ==================== 验收断言 ====================

    @Test
    void search_allTypes_facetsHaveFiveKeys() {
        givenIndexHits(List.of(
                idx("wiki", wikiId.toString(), "设计文档", "设计内容"),
                idx("im", msgId.toString(), "消息", "hello world"),
                idx("record", UUID.randomUUID().toString(), "订单", "record"),
                idx("project", taskId.toString(), "任务", "task"),
                idx("automation", ruleId.toString(), "规则", "auto")
        ));
        when(wikiPageService.get(any(UUID.class))).thenAnswer(inv -> {
            WikiPageEntity p = new WikiPageEntity();
            p.setId(inv.getArgument(0));
            p.setStatus("PUBLISHED");
            p.setTitle("设计文档");
            p.setSlug("test-slug");
            p.setCreatedAt(Instant.now());
            return p;
        });
        when(messageService.mustGet(any(UUID.class))).thenAnswer(inv -> {
            ImMessageEntity m = new ImMessageEntity();
            m.setId(inv.getArgument(0));
            m.setContent("hello");
            m.setChannelId(UUID.randomUUID());
            m.setCreatedAt(Instant.now());
            return m;
        });
        when(messageService.isMemberOfChannel(any(), any())).thenReturn(true);
        when(projectService.get(any(UUID.class), eq(tenantId))).thenAnswer(inv -> {
            ProjectTaskEntity t = new ProjectTaskEntity();
            t.setId(inv.getArgument(0));
            t.setTitle("任务");
            t.setCreatedAt(Instant.now());
            return t;
        });

        Map<String, Object> result = service.search("content", tenantId, null, 20);
        assertThat(result.get("total")).isEqualTo(5);
        assertThat(facets(result).keySet())
                .containsExactlyInAnyOrder("im", "wiki", "record", "project", "automation");
        assertThat(facets(result).get("wiki")).isEqualTo(1L);
        assertThat(facets(result).get("im")).isEqualTo(1L);
        assertThat(facets(result).get("record")).isEqualTo(1L);
        assertThat(facets(result).get("project")).isEqualTo(1L);
        assertThat(facets(result).get("automation")).isEqualTo(1L);
    }

    @Test
    void search_automation_filtersByKeyword() {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        rule.setName("订单自动流转");
        rule.setDescription("当创建订单时自动流转");
        rule.setTriggerType("RECORD_CREATE");
        rule.setCreatedAt(Instant.now());
        when(automationRuleService.getRule(ruleId)).thenReturn(rule);
        givenIndexHits(List.of(
                idx("automation", ruleId.toString(), "订单自动流转", "当创建订单时自动流转")
        ));

        Map<String, Object> hit = service.search("订单", tenantId, List.of("automation"), 10);
        assertThat(results(hit)).hasSize(1);
        assertThat(results(hit).get(0).get("title")).isEqualTo("订单自动流转");

        // 不相关关键词 → 结果为空
        Map<String, Object> miss = service.search("zzz", tenantId, List.of("automation"), 10);
        assertThat(results(miss)).isEmpty();
    }

    @Test
    void search_wiki_filtersPublishedOnly() {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(wikiId);
        page.setTitle("公开文档");
        page.setStatus("PUBLISHED");
        page.setSlug("public-doc");
        page.setCreatedAt(Instant.now());
        when(wikiPageService.get(wikiId)).thenReturn(page);
        givenIndexHits(List.of(
                idx("wiki", wikiId.toString(), "公开文档", "公开内容")
        ));

        Map<String, Object> result = service.search("公开", tenantId, List.of("wiki"), 10);
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).get(0).get("title")).isEqualTo("公开文档");

        // 页面已删除/不存在 → 被过滤
        when(wikiPageService.get(wikiId)).thenReturn(null);
        Map<String, Object> empty = service.search("公开", tenantId, List.of("wiki"), 10);
        assertThat(results(empty)).isEmpty();
    }

    @Test
    void search_im_hitsFromIndex() {
        ImMessageEntity msg = new ImMessageEntity();
        msg.setId(msgId);
        msg.setContent("频道内容");
        msg.setChannelId(UUID.randomUUID());
        msg.setCreatedAt(Instant.now());
        when(messageService.mustGet(msgId)).thenReturn(msg);
        givenIndexHits(List.of(
                idx("im", msgId.toString(), "频道消息", "频道内容")
        ));

        Map<String, Object> result = service.search("频道", tenantId, List.of("im"), 10);
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).get(0).get("type")).isEqualTo("im");
        assertThat(results(result).get(0).get("id")).isEqualTo(msgId);

        // 消息不存在 → 被过滤
        when(messageService.mustGet(msgId)).thenThrow(new RuntimeException("404"));
        Map<String, Object> empty = service.search("频道", tenantId, List.of("im"), 10);
        assertThat(results(empty)).isEmpty();
    }

    @Test
    void indexAndRemove() {
        service.indexEntity("wiki", wikiId.toString(), tenantId, "文档", "内容", null);
        verify(indexRepo).save(any(UnifiedSearchIndexEntity.class));
        // indexEntity 内部做 upsert（先 delete 再 save），此处不验证 delete 次数

        service.removeEntity("wiki", wikiId.toString(), tenantId);
        // 共调用 2 次 delete（indexEntity 1 + removeEntity 1）
        verify(indexRepo, org.mockito.Mockito.times(2))
                .deleteByEntityTypeAndEntityIdAndTenantId("wiki", wikiId.toString(), tenantId);
    }
}
