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
import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.project.ProjectService;
import com.nocobase.project.ProjectTaskEntity;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 全局搜索服务测试 — 验证"真接真"：
 * 1. 写入索引后搜索能命中（五类）
 * 2. facets 含全部六键（im/wiki/record/project/automation/approval）
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
    private ImChannelMemberRepository channelMemberRepository;
    private ObjectMapper objectMapper;
    private com.nocobase.auth.AclEnforcer aclEnforcer;
    private com.nocobase.acl.RowAclService rowAclService;
    private com.nocobase.auth.RoleRepository roleRepository;
    private com.nocobase.wiki.WikiPermissionService wikiPermissionService;
    private UnifiedSearchService service;
    private com.nocobase.approval.ApprovalRequestRepository approvalRepository;
    private com.nocobase.approval.ApprovalVisibilityService approvalVisibility;
    private static final UUID USER = UUID.randomUUID();

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
        channelMemberRepository = mock(ImChannelMemberRepository.class);
        objectMapper = new ObjectMapper();
        aclEnforcer = mock(com.nocobase.auth.AclEnforcer.class);
        rowAclService = mock(com.nocobase.acl.RowAclService.class);
        roleRepository = mock(com.nocobase.auth.RoleRepository.class);
        wikiPermissionService = mock(com.nocobase.wiki.WikiPermissionService.class);
        // A-3：默认放行（无策略 = 放行），越权拒绝在专门用例中断言
        when(aclEnforcer.isAllowed(any(), any(), any(), any())).thenReturn(true);
        when(rowAclService.evaluateRead(any(), any(), any(), any())).thenReturn(true);
        when(rowAclService.policyPresence(any(), any(), any()))
                .thenReturn(com.nocobase.acl.RowAclService.PolicyPresence.ABSENT);
        when(roleRepository.findRoleNamesByUserId(any(), any())).thenReturn(java.util.List.of("USER"));
        when(aclEnforcer.filterRecordsByDataScope(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
        when(aclEnforcer.resolveScopePlan(any(), any()))
                .thenReturn(new com.nocobase.auth.AclEnforcer.ScopePlan(
                        com.nocobase.auth.AclEnforcer.ScopeKind.ALL, null, null, false));
        when(aclEnforcer.filterInScope(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(wikiPermissionService.hasPagePermission(any(), any(), any(), any())).thenReturn(true);
        when(channelMemberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(true);
        when(collectionService.getRecord(any(), any(), any()))
                .thenAnswer(inv -> new java.util.HashMap<>(Map.of("id", inv.getArgument(1))));
        when(collectionService.getRecordsByIds(any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.Collection<String> ids = inv.getArgument(1);
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    for (String id : ids) {
                        Map<String, Object> m = new java.util.HashMap<>();
                        m.put("id", id);
                        out.add(m);
                    }
                    return out;
                });
        approvalRepository = mock(com.nocobase.approval.ApprovalRequestRepository.class);
        approvalVisibility = mock(com.nocobase.approval.ApprovalVisibilityService.class);
        service = new UnifiedSearchService(
                messageService, wikiPageService, collectionService,
                automationRuleService, projectService, indexRepo,
                channelMemberRepository, objectMapper,
                aclEnforcer, rowAclService, roleRepository, wikiPermissionService,
                approvalRepository,
                mock(com.nocobase.approval.ApprovalCcRepository.class),
                mock(com.nocobase.workflow.WorkflowTaskRepository.class),
                approvalVisibility);
    }

    // ---- 辅助：构造索引命中实体 ----

    private UnifiedSearchIndexEntity idx(String entityType, String entityId,
                                          String title, String content) {
        UnifiedSearchIndexEntity e = new UnifiedSearchIndexEntity();
        e.setId(UUID.randomUUID());
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setTenantId(tenantId);
        if ("record".equals(entityType)) {
            e.setMetadataJson("{\"collection\":\"orders\",\"recordId\":\"" + entityId + "\"}");
        }
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

        Map<String, Object> result = service.search("content", tenantId, USER, null, 20);
        assertThat(result.get("total")).isEqualTo(5);
        assertThat(facets(result).keySet())
                .containsExactlyInAnyOrder("im", "wiki", "record", "project", "automation", "approval");
        assertThat(facets(result).get("wiki")).isEqualTo(1L);
        assertThat(facets(result).get("im")).isEqualTo(1L);
        assertThat(facets(result).get("record")).isEqualTo(1L);
        assertThat(facets(result).get("project")).isEqualTo(1L);
        assertThat(facets(result).get("automation")).isEqualTo(1L);
    }

    @Test
    void search_records_aclContextQueriesOncePerRequest() {
        String r1 = UUID.randomUUID().toString();
        String r2 = UUID.randomUUID().toString();
        givenIndexHits(List.of(
                idx("record", r1, "订单 A", "content"),
                idx("record", r2, "订单 B", "content")));

        Map<String, Object> result = service.search("content", tenantId, USER,
                List.of("record"), 20);

        assertThat(result.get("total")).isEqualTo(2);
        // R4：角色只查 1 次（不是每条命中 1 次）
        verify(roleRepository, org.mockito.Mockito.times(1))
                .findRoleNamesByUserId(USER, tenantId);
        // 批量读取 1 次（不是逐条 getRecord）
        verify(collectionService, org.mockito.Mockito.times(1))
                .getRecordsByIds(eq("orders"), any(), eq(tenantId));
        verify(collectionService, org.mockito.Mockito.never())
                .getRecord(any(), any(), any());
        // 集合权限按 collection 记忆化：2 条命中只判 1 次
        verify(aclEnforcer, org.mockito.Mockito.times(1))
                .isAllowed(eq(USER), eq(tenantId), eq("orders"), any());
        // F5：ROW 策略存在性同样按集合只查 1 次
        verify(rowAclService, org.mockito.Mockito.times(1))
                .policyPresence(tenantId, "orders", "read");
    }

    @Test
    void search_records_noRowPolicy_isVisibleLikeListPath() {
        String r1 = UUID.randomUUID().toString();
        givenIndexHits(List.of(idx("record", r1, "订单 A", "content")));
        // 无 ROW 策略：evaluateRead 本身是 fail-closed，但列表路径的语义是「无策略=放行」，
        // 搜索必须与列表同源，否则未配置行级权限的集合在搜索里整体消失。
        when(rowAclService.policyPresence(tenantId, "orders", "read"))
                .thenReturn(com.nocobase.acl.RowAclService.PolicyPresence.ABSENT);
        when(rowAclService.evaluateRead(any(), any(), any(), any())).thenReturn(false);

        Map<String, Object> result = service.search("content", tenantId, USER,
                List.of("record"), 20);

        assertThat(result.get("total")).isEqualTo(1);
        verify(rowAclService, org.mockito.Mockito.never())
                .evaluateRead(any(), any(), any(), any());
    }

    @Test
    void search_records_rowPolicyDeny_filtersOut() {
        String r1 = UUID.randomUUID().toString();
        givenIndexHits(List.of(idx("record", r1, "订单 A", "content")));
        when(rowAclService.policyPresence(tenantId, "orders", "read"))
                .thenReturn(com.nocobase.acl.RowAclService.PolicyPresence.PRESENT);
        when(rowAclService.evaluateRead(any(), any(), any(), any())).thenReturn(false);

        Map<String, Object> result = service.search("content", tenantId, USER,
                List.of("record"), 20);

        assertThat(result.get("total")).isEqualTo(0);
    }

    @Test
    void search_records_rowPolicyPresenceQueriedOncePerCollection() {
        String r1 = UUID.randomUUID().toString();
        String r2 = UUID.randomUUID().toString();
        UnifiedSearchIndexEntity ordersHit = idx("record", r1, "订单 A", "content");
        UnifiedSearchIndexEntity usersHit = idx("record", r2, "用户 B", "content");
        usersHit.setMetadataJson("{\"collection\":\"users\",\"recordId\":\"" + r2 + "\"}");
        givenIndexHits(List.of(ordersHit, usersHit));

        service.search("content", tenantId, USER, List.of("record"), 20);

        verify(rowAclService, org.mockito.Mockito.times(1))
                .policyPresence(tenantId, "orders", "read");
        verify(rowAclService, org.mockito.Mockito.times(1))
                .policyPresence(tenantId, "users", "read");
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

        Map<String, Object> hit = service.search("订单", tenantId, USER, List.of("automation"), 10);
        assertThat(results(hit)).hasSize(1);
        assertThat(results(hit).get(0).get("title")).isEqualTo("订单自动流转");

        // 不相关关键词 → 结果为空
        Map<String, Object> miss = service.search("zzz", tenantId, USER, List.of("automation"), 10);
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

        Map<String, Object> result = service.search("公开", tenantId, USER, List.of("wiki"), 10);
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).get(0).get("title")).isEqualTo("公开文档");

        // 页面已删除/不存在 → 被过滤
        when(wikiPageService.get(wikiId)).thenReturn(null);
        Map<String, Object> empty = service.search("公开", tenantId, USER, List.of("wiki"), 10);
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
        com.nocobase.im.entity.ImChannelMemberEntity member =
                new com.nocobase.im.entity.ImChannelMemberEntity();
        member.setChannelId(msg.getChannelId());
        member.setUserId(USER);
        member.setTenantId(tenantId);
        when(channelMemberRepository.findByTenantIdAndUserId(tenantId, USER))
                .thenReturn(List.of(member));
        givenIndexHits(List.of(
                idx("im", msgId.toString(), "频道消息", "频道内容")
        ));

        Map<String, Object> result = service.search("频道", tenantId, USER, List.of("im"), 10);
        assertThat(results(result)).hasSize(1);
        assertThat(results(result).get(0).get("type")).isEqualTo("im");
        assertThat(results(result).get(0).get("id")).isEqualTo(msgId);

        // 消息不存在 → 被过滤
        when(messageService.mustGet(msgId)).thenThrow(new RuntimeException("404"));
        Map<String, Object> empty = service.search("频道", tenantId, USER, List.of("im"), 10);
        assertThat(results(empty)).isEmpty();
    }

    @Test
    void indexAndRemove() {
        when(indexRepo.findByEntityTypeAndEntityIdAndTenantId("wiki", wikiId.toString(), tenantId))
                .thenReturn(List.of());
        service.indexEntity("wiki", wikiId.toString(), tenantId, "文档", "内容", null);
        verify(indexRepo).save(any(UnifiedSearchIndexEntity.class));

        service.removeEntity("wiki", wikiId.toString(), tenantId);
        verify(indexRepo).deleteByEntityTypeAndEntityIdAndTenantId(
                "wiki", wikiId.toString(), tenantId);
    }

    @Test
    void indexEntity_existing_reusesId() {
        UnifiedSearchIndexEntity existing = new UnifiedSearchIndexEntity();
        existing.setId(UUID.randomUUID());
        existing.setCreatedAt(java.time.Instant.now());
        when(indexRepo.findByEntityTypeAndEntityIdAndTenantId("wiki", wikiId.toString(), tenantId))
                .thenReturn(List.of(existing));
        UUID originalId = existing.getId();

        service.indexEntity("wiki", wikiId.toString(), tenantId, "新标题", "新内容", null);

        assertThat(existing.getId()).isEqualTo(originalId);
        assertThat(existing.getTitle()).isEqualTo("新标题");
        verify(indexRepo).save(existing);
    }

    // ==================== PHASE105 补充分支 ====================

    @Test
    void search_searchRecord_indexQueryThrows_returnsEmpty() {
        // record 索引查询抛异常 → 走 catch 返回空列表，不阻断整体检索
        when(indexRepo.searchByKeywordAndType(anyString(), anyString(), eq(tenantId), anyInt()))
                .thenAnswer(inv -> {
                    if ("record".equals(inv.getArgument(0))) {
                        throw new RuntimeException("index down");
                    }
                    return List.of();
                });

        Map<String, Object> result = service.search("kw", tenantId, USER, List.of("record"), 10);
        assertThat(result.get("total")).isEqualTo(0);
    }

    @Test
    void search_searchProject_taskNotFound_filteredOut() {
        UUID taskId = UUID.randomUUID();
        when(indexRepo.searchByKeywordAndType(eq("project"), eq("kw"), eq(tenantId), eq(10)))
                .thenReturn(List.of(idx("project", taskId.toString(), "任务", "snippet")));
        when(projectService.get(eq(taskId), eq(tenantId))).thenReturn(null);

        Map<String, Object> result = service.search("kw", tenantId, USER, List.of("project"), 10);

        assertThat(results(result)).isEmpty();
    }

    @Test
    void search_searchProject_taskFound_included() {
        UUID taskId = UUID.randomUUID();
        when(indexRepo.searchByKeywordAndType(eq("project"), eq("kw"), eq(tenantId), eq(10)))
                .thenReturn(List.of(idx("project", taskId.toString(), "任务", "snippet")));
        ProjectTaskEntity task = new ProjectTaskEntity();
        task.setId(taskId);
        task.setTitle("任务A");
        task.setCreatedAt(Instant.now());
        when(projectService.get(eq(taskId), eq(tenantId))).thenReturn(task);

        Map<String, Object> result = service.search("kw", tenantId, USER, List.of("project"), 10);

        List<Map<String, Object>> rows = results(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(taskId);
        assertThat(rows.get(0).get("title")).isEqualTo("任务A");
    }

    @Test
    void globalApprovalResultsUseSameVisibilityPredicate() {
        UUID requestId = UUID.randomUUID();
        var request = new com.nocobase.approval.ApprovalRequestEntity();
        request.setTenantId(tenantId);
        when(approvalRepository.findByIdAndTenantId(requestId, tenantId))
                .thenReturn(java.util.Optional.of(request));
        when(approvalVisibility.canView(tenantId, request, USER)).thenReturn(false);
        when(indexRepo.searchAllTypes(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(idx("approval", requestId.toString(), "请假", "请假")));
        assertThat(results(service.search("请假", tenantId, USER, List.of(), 10))).isEmpty();
        verify(approvalVisibility).canView(tenantId, request, USER);
    }

    @Test
    void searchApproval_expandsCandidateWindowBeforeVisibilityFilter() {
        when(indexRepo.searchByKeywordAndType(eq("approval"), eq("请假"), eq(tenantId), anyInt()))
                .thenReturn(List.of());

        service.search("请假", tenantId, USER, List.of("approval"), 5);

        // 可见性在 Java 侧过滤，候选窗口不能等于展示 limit，否则普通用户自己的单据会被截掉
        verify(indexRepo).searchByKeywordAndType(eq("approval"), eq("请假"), eq(tenantId), eq(100));
    }
}
