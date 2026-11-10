package com.nocobase.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.approval.ApprovalCcRepository;
import com.nocobase.approval.ApprovalRequestRepository;
import com.nocobase.approval.ApprovalService;
import com.nocobase.approval.ApprovalTemplateEntity;
import com.nocobase.approval.ApprovalTemplateRepository;
import com.nocobase.approval.ApprovalVisibilityService;
import com.nocobase.auth.DeptScopeResolver;
import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.FieldDef;
import com.nocobase.search.SearchIndexSyncListener;
import com.nocobase.search.UnifiedSearchService;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R5：真实 PostgreSQL 集成测试（Testcontainers，标签 {@code pg}）。
 *
 * <p>修复前 {@code scripts/verify-enterprise-suite.py} 手写等价 SQL 断言 DEPT、
 * 直接 INSERT 索引 —— 只证明 SQL 语义，不证明 Java 实现。本测试走**生产代码路径**：
 * <ul>
 *   <li>{@link DeptScopeResolver#resolveVisibleCreatorIds}（真实 org_unit/users 表）</li>
 *   <li>{@link SearchIndexSyncListener#reindexRecord}（生产写入器推断 title/content）
 *       写出的内容在真实 {@code content_tsv} 上可命中</li>
 *   <li>{@link ApprovalService#submit} 的 MANAGER 有/无 manager_id 两条路径</li>
 * </ul>
 *
 * <p>无 Docker 时由 {@code @Testcontainers(disabledWithoutDocker = true)} 显式 SKIP，
 * 门禁把 SKIP 单列，不计 PASS。
 */
@Tag("pg")
@Testcontainers(disabledWithoutDocker = true)
class EnterpriseSuitePgIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static final String TENANT = "tenant_pg_it";

    @BeforeAll
    static void initSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE org_unit (
                    id UUID PRIMARY KEY,
                    tenant_id VARCHAR(64) NOT NULL,
                    parent_id UUID,
                    name VARCHAR(255),
                    path TEXT,
                    sort INT DEFAULT 0,
                    source VARCHAR(32) DEFAULT 'LOCAL',
                    deleted BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW()
                )""");
        jdbc.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY,
                    username VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    department_id UUID,
                    manager_id UUID,
                    password_set BOOLEAN NOT NULL DEFAULT TRUE,
                    status VARCHAR(32) DEFAULT 'ACTIVE',
                    enabled BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                )""");
        jdbc.execute("""
                CREATE TABLE unified_search_index (
                    id UUID PRIMARY KEY,
                    entity_type VARCHAR(64) NOT NULL,
                    entity_id VARCHAR(256) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    title VARCHAR(512),
                    content TEXT,
                    content_tsv tsvector,
                    metadata JSONB DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW(),
                    UNIQUE(entity_type, entity_id, tenant_id)
                )""");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION update_search_vector() RETURNS TRIGGER AS $$
                BEGIN
                    NEW.content_tsv := to_tsvector('simple',
                        COALESCE(NEW.title, '') || ' ' || COALESCE(NEW.content, ''));
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("""
                CREATE TRIGGER trg_search_vector BEFORE INSERT OR UPDATE
                ON unified_search_index FOR EACH ROW
                EXECUTE FUNCTION update_search_vector()""");
    }

    // ------------------------------------------------------------
    //  1. DEPT：真实子树解析
    // ------------------------------------------------------------

    @Test
    @DisplayName("DeptScopeResolver：子树成员可见、平级部门排除、无部门降级 SELF")
    void deptScope_realSubtree() {
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        UUID uRoot = UUID.randomUUID();
        UUID uChild = UUID.randomUUID();
        UUID uSibling = UUID.randomUUID();
        UUID uNoDept = UUID.randomUUID();

        jdbc.update("DELETE FROM users WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM org_unit WHERE tenant_id = ?", TENANT);
        jdbc.update("""
                INSERT INTO org_unit (id, tenant_id, parent_id, name, path) VALUES
                (?, ?, NULL, 'A', ?), (?, ?, ?, 'B', ?), (?, ?, NULL, 'C', ?)""",
                root, TENANT, "/" + root + "/",
                child, TENANT, root, "/" + root + "/" + child + "/",
                sibling, TENANT, "/" + sibling + "/");
        jdbc.update("""
                INSERT INTO users (id, username, tenant_id, department_id) VALUES
                (?, 'uA', ?, ?), (?, 'uB', ?, ?), (?, 'uC', ?, ?), (?, 'uNone', ?, NULL)""",
                uRoot, TENANT, root,
                uChild, TENANT, child,
                uSibling, TENANT, sibling,
                uNoDept, TENANT);

        DeptScopeResolver resolver = new DeptScopeResolver(jdbc);

        // A 部门负责人：A + 子部门 B
        Set<UUID> fromA = resolver.resolveVisibleCreatorIds(uRoot, TENANT);
        assertThat(fromA).contains(uRoot, uChild).doesNotContain(uSibling);

        // B 部门成员：仅 B 子树
        Set<UUID> fromB = resolver.resolveVisibleCreatorIds(uChild, TENANT);
        assertThat(fromB).contains(uChild).doesNotContain(uRoot, uSibling);

        // 无部门 → 仅自己
        Set<UUID> fromNone = resolver.resolveVisibleCreatorIds(uNoDept, TENANT);
        assertThat(fromNone).containsExactly(uNoDept);
    }

    // ------------------------------------------------------------
    //  2. 生产索引写入器 → 真实 content_tsv 命中
    // ------------------------------------------------------------

    @Test
    @DisplayName("SearchIndexSyncListener.reindexRecord：生产推断的 title/content 可被全文检索命中")
    void reindexRecord_productionWriter_searchable() {
        UnifiedSearchService searchService = mock(UnifiedSearchService.class);
        CollectionService collectionService = mock(CollectionService.class);
        SearchIndexSyncListener listener = new SearchIndexSyncListener(searchService,
                mock(com.nocobase.wiki.WikiPageService.class),
                mock(com.nocobase.im.MessageService.class),
                mock(com.nocobase.project.ProjectService.class),
                collectionService);

        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("customer");
        meta.setTenantId(TENANT);
        meta.setFieldsJson("[]");
        when(collectionService.get("customer", TENANT)).thenReturn(meta);
        when(collectionService.parseFields(meta)).thenReturn(List.of(
                new FieldDef("name", "text", false, "客户名称", Map.of(), false, false, null, null),
                new FieldDef("remark", "text", false, "备注", Map.of(), false, false, null, null)));

        String recordId = UUID.randomUUID().toString();
        listener.reindexRecord("customer", recordId, TENANT,
                Map.of("name", "East China Manufacturing Ltd",
                        "remark", "annual purchase framework agreement"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(searchService).indexEntity(eq("record"), eq(recordId),
                eq(TENANT), captor.capture(), captor.capture(), any());
        List<String> values = captor.getAllValues();
        String title = values.get(0);
        String content = values.get(1);
        assertThat(title).isEqualTo("East China Manufacturing Ltd");
        assertThat(content).contains("annual purchase framework agreement");

        // 用生产写入器的输出在真实表上建索引 → 真实 tsvector 命中
        jdbc.update("""
                INSERT INTO unified_search_index
                    (id, entity_type, entity_id, tenant_id, title, content, metadata)
                VALUES (?, 'record', ?, ?, ?, ?, '{"collection":"customer"}'::jsonb)""",
                UUID.randomUUID(), recordId, TENANT, title, content);
        Integer hits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM unified_search_index
                WHERE tenant_id = ? AND entity_type = 'record'
                  AND content_tsv @@ plainto_tsquery('simple', 'purchase')""",
                Integer.class, TENANT);
        assertThat(hits).isEqualTo(1);
    }

    // ------------------------------------------------------------
    //  3. ApprovalService.submit：MANAGER 有/无 manager_id
    // ------------------------------------------------------------

    @Test
    @DisplayName("ApprovalService.submit：MANAGER 无 manager_id → 明确拒绝（不建实例）")
    void approvalSubmit_managerMissing_rejected() {
        ApprovalService service = approvalService();
        UUID templateId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();

        ApprovalTemplateEntity t = new ApprovalTemplateEntity();
        t.setId(templateId);
        t.setTenantId(TENANT);
        t.setName("请假");
        t.setWorkflowId(UUID.randomUUID());
        t.setEnabled(true);
        when(serviceRepository(service, "templateRepository", ApprovalTemplateRepository.class)
                .findByIdAndTenantId(templateId, TENANT)).thenReturn(Optional.of(t));

        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(t.getWorkflowId());
        wf.setTenantId(TENANT);
        wf.setName("wf");
        wf.setCreatedBy(applicant);
        wf.setNodesJson("[{\"id\":\"a1\",\"type\":\"APPROVAL\",\"config\":{\"approverType\":\"MANAGER\"}}]");
        wf.setEdgesJson("[]");
        when(serviceRepository(service, "workflowRepository", WorkflowRepository.class)
                .findByIdAndTenantId(t.getWorkflowId(), TENANT)).thenReturn(Optional.of(wf));

        UserEntity applicantEntity = new UserEntity();
        applicantEntity.setId(applicant);
        applicantEntity.setTenantId(TENANT);
        applicantEntity.setDisplayName("张三");
        when(serviceRepository(service, "userRepository", UserRepository.class)
                .findByIdAndTenantId(applicant, TENANT)).thenReturn(Optional.of(applicantEntity));

        assertThatThrownBy(() -> service.submit(TENANT, applicant, templateId, Map.of(), List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(400));
    }

    @Test
    @DisplayName("ApprovalService.submit：MANAGER 有 manager_id → 通过校验并创建实例")
    void approvalSubmit_managerPresent_proceeds() {
        ApprovalService service = approvalService();
        UUID templateId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();
        UUID manager = UUID.randomUUID();

        ApprovalTemplateEntity t = new ApprovalTemplateEntity();
        t.setId(templateId);
        t.setTenantId(TENANT);
        t.setName("请假");
        t.setWorkflowId(UUID.randomUUID());
        t.setEnabled(true);
        when(serviceRepository(service, "templateRepository", ApprovalTemplateRepository.class)
                .findByIdAndTenantId(templateId, TENANT)).thenReturn(Optional.of(t));

        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(t.getWorkflowId());
        wf.setTenantId(TENANT);
        wf.setName("wf");
        wf.setCreatedBy(applicant);
        wf.setNodesJson("[{\"id\":\"a1\",\"type\":\"APPROVAL\",\"config\":{\"approverType\":\"MANAGER\"}}]");
        wf.setEdgesJson("[]");
        when(serviceRepository(service, "workflowRepository", WorkflowRepository.class)
                .findByIdAndTenantId(t.getWorkflowId(), TENANT)).thenReturn(Optional.of(wf));

        UserEntity applicantEntity = new UserEntity();
        applicantEntity.setId(applicant);
        applicantEntity.setTenantId(TENANT);
        applicantEntity.setDisplayName("张三");
        applicantEntity.setManagerId(manager);
        when(serviceRepository(service, "userRepository", UserRepository.class)
                .findByIdAndTenantId(applicant, TENANT)).thenReturn(Optional.of(applicantEntity));

        var requestRepo = serviceRepository(service, "requestRepository",
                ApprovalRequestRepository.class);
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var instanceRepo = serviceRepository(service, "instanceRepository",
                WorkflowInstanceRepository.class);
        when(instanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var taskRepo = serviceRepository(service, "taskRepository", WorkflowTaskRepository.class);
        when(taskRepo.findByInstanceIdAndStatus(any(), any())).thenReturn(List.of());
        when(taskRepo.findByInstanceId(any())).thenReturn(List.of());

        Map<String, Object> detail = service.submit(TENANT, applicant, templateId,
                Map.of("days", 2), List.of());
        assertThat(detail).containsKeys("id", "status");
    }

    @Test
    @DisplayName("A0-4 真实 UnifiedSearchService.indexEntity：repository upsert + content_tsv 命中")
    void indexEntity_realService_upsertsAndSearchable() {
        com.nocobase.search.UnifiedSearchIndexRepository repo =
                mock(com.nocobase.search.UnifiedSearchIndexRepository.class);
        when(repo.findByEntityTypeAndEntityIdAndTenantId(any(), any(), any()))
                .thenAnswer(inv -> jdbc.query(
                        "SELECT * FROM unified_search_index WHERE entity_type=? AND entity_id=? AND tenant_id=?",
                        (rs, i) -> {
                            com.nocobase.search.UnifiedSearchIndexEntity e =
                                    new com.nocobase.search.UnifiedSearchIndexEntity();
                            e.setId(UUID.fromString(rs.getString("id")));
                            e.setEntityType(rs.getString("entity_type"));
                            e.setEntityId(rs.getString("entity_id"));
                            e.setTenantId(rs.getString("tenant_id"));
                            e.setTitle(rs.getString("title"));
                            e.setContent(rs.getString("content"));
                            e.setMetadataJson(rs.getString("metadata"));
                            return e;
                        },
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(repo.save(any())).thenAnswer(inv -> {
            com.nocobase.search.UnifiedSearchIndexEntity e = inv.getArgument(0);
            jdbc.update("""
                    INSERT INTO unified_search_index
                        (id, entity_type, entity_id, tenant_id, title, content, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (entity_type, entity_id, tenant_id)
                    DO UPDATE SET title=EXCLUDED.title, content=EXCLUDED.content,
                                  metadata=EXCLUDED.metadata, updated_at=NOW()""",
                    e.getId(), e.getEntityType(), e.getEntityId(), e.getTenantId(),
                    e.getTitle(), e.getContent(), e.getMetadataJson());
            return e;
        });

        UnifiedSearchService svc = new UnifiedSearchService(
                mock(com.nocobase.im.MessageService.class),
                mock(com.nocobase.wiki.WikiPageService.class),
                mock(CollectionService.class),
                mock(com.nocobase.automation.AutomationRuleService.class),
                mock(com.nocobase.project.ProjectService.class),
                repo,
                mock(com.nocobase.im.ImChannelMemberRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.nocobase.auth.AclEnforcer.class),
                mock(com.nocobase.acl.RowAclService.class),
                mock(RoleRepository.class),
                mock(com.nocobase.wiki.WikiPermissionService.class),
                mock(com.nocobase.approval.ApprovalRequestRepository.class),
                mock(ApprovalCcRepository.class),
                mock(WorkflowTaskRepository.class),
                mock(ApprovalVisibilityService.class));

        String id = UUID.randomUUID().toString();
        svc.indexEntity("wiki", id, TENANT, "年度目标", "季度预算与采购计划",
                java.util.Map.of("kind", "wiki"));

        Integer hits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM unified_search_index
                WHERE tenant_id = ? AND content_tsv @@ plainto_tsquery('simple', '年度目标')""",
                Integer.class, TENANT);
        String title = jdbc.queryForObject(
                "SELECT title FROM unified_search_index WHERE entity_type='wiki' AND entity_id=?",
                String.class, id);
        assertThat(title).isEqualTo("年度目标");
        assertThat(hits).isEqualTo(1);

        // 同一 (type,id,tenant) 再写 → upsert，不触发唯一键冲突且计数仍为 1
        svc.indexEntity("wiki", id, TENANT, "新标题", "预算更新", java.util.Map.of());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM unified_search_index
                WHERE entity_type='wiki' AND entity_id=? AND tenant_id=?""",
                Integer.class, id, TENANT);
        assertThat(count).isEqualTo(1);
        String updated = jdbc.queryForObject(
                "SELECT title FROM unified_search_index WHERE entity_type='wiki' AND entity_id=?",
                String.class, id);
        assertThat(updated).isEqualTo("新标题");
    }

    @Test
    @DisplayName("A0-4 V81 从 V79 状态真实升级：类型回填 + last_event_at")
    void v81_migration_upgradesFromV79() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS messages");
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
        jdbc.execute("""
                CREATE TABLE messages (
                    id UUID PRIMARY KEY,
                    type VARCHAR(64) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
        jdbc.execute("""
                CREATE TABLE flyway_schema_history (
                    version VARCHAR(64), installed_on TIMESTAMPTZ NOT NULL
                )""");
        jdbc.update("INSERT INTO flyway_schema_history VALUES ('79', '2026-01-01T00:00:00Z')");

        UUID legacy = UUID.randomUUID();
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        jdbc.update("INSERT INTO messages (id, type, created_at) VALUES (?, 'workflow', NOW())", legacy);
        jdbc.update("INSERT INTO messages (id, type, created_at) VALUES (?, 'WORKFLOW_FAILED', '2025-12-01T00:00:00Z')", before);
        jdbc.update("INSERT INTO messages (id, type, created_at) VALUES (?, 'WORKFLOW_FAILED', '2026-02-01T00:00:00Z')", after);

        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V81__notification_type_backfill_and_last_event.sql"));
        jdbc.execute(sql);

        assertThat(jdbc.queryForObject("SELECT type FROM messages WHERE id=?", String.class, legacy))
                .isEqualTo("WORKFLOW_NOTICE");
        assertThat(jdbc.queryForObject("SELECT type FROM messages WHERE id=?", String.class, before))
                .isEqualTo("WORKFLOW_NOTICE");
        assertThat(jdbc.queryForObject("SELECT type FROM messages WHERE id=?", String.class, after))
                .isEqualTo("WORKFLOW_FAILED");
        Integer col = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='messages' AND column_name='last_event_at'""", Integer.class);
        assertThat(col).isEqualTo(1);
    }

    @Test
    @DisplayName("A0-5 V81 无 flyway 历史库：不误改真实失败、可重复执行")
    void v81_noHistory_safeAndIdempotent() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS messages");
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
        jdbc.execute("""
                CREATE TABLE messages (
                    id UUID PRIMARY KEY,
                    type VARCHAR(64) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
        UUID legacy = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        jdbc.update("INSERT INTO messages (id, type) VALUES (?, 'workflow')", legacy);
        jdbc.update("INSERT INTO messages (id, type) VALUES (?, 'WORKFLOW_FAILED')", failed);

        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V81__notification_type_backfill_and_last_event.sql"));
        jdbc.execute(sql);
        jdbc.execute(sql); // 幂等：重复执行不报错

        assertThat(jdbc.queryForObject("SELECT type FROM messages WHERE id=?", String.class, legacy))
                .isEqualTo("WORKFLOW_NOTICE");
        assertThat(jdbc.queryForObject("SELECT type FROM messages WHERE id=?", String.class, failed))
                .isEqualTo("WORKFLOW_FAILED");
        Integer col = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name='messages' AND column_name='last_event_at'""", Integer.class);
        assertThat(col).isEqualTo(1);
    }

    // ------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------

    private static ApprovalService approvalService() {
        ApprovalTemplateRepository templateRepository = mock(ApprovalTemplateRepository.class);
        ApprovalRequestRepository requestRepository = mock(ApprovalRequestRepository.class);
        ApprovalCcRepository ccRepository = mock(ApprovalCcRepository.class);
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
        WorkflowTaskRepository taskRepository = mock(WorkflowTaskRepository.class);
        WorkflowEngine engine = mock(WorkflowEngine.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(ccRepository.findByRequestId(any())).thenReturn(List.of());
        when(engine.executeFrom(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);
        when(engine.executeGraphFrom(any(), any(), any(), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.nocobase.approval.ApprovalSubmissionService> submissions =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        var submission = mock(com.nocobase.approval.ApprovalSubmissionService.class);
        when(submissions.getObject()).thenReturn(submission);
        when(engine.executeFrom(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);
        ApprovalService[] holder = new ApprovalService[1];
        when(submission.submit(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(holder[0],
                        "submitLocal", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4)));
        ApprovalService result = new ApprovalService(templateRepository, requestRepository, ccRepository,
                workflowRepository, instanceRepository, taskRepository, engine, userRepository,
                 mock(com.nocobase.integration.dingtalk.DingTalkExternalApprovalService.class),
                 submissions,
                 mock(com.nocobase.notification.NotifyDispatcher.class),
                mock(UnifiedSearchService.class),
                mock(ApprovalVisibilityService.class),
                 false);
        holder[0] = result;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T serviceRepository(ApprovalService service, String field, Class<T> type) {
        return (T) org.springframework.test.util.ReflectionTestUtils.getField(service, field);
    }
}
