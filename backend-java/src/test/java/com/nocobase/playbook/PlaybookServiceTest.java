package com.nocobase.playbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.im.MessageService;
import com.nocobase.wiki.KnowledgeBaseEntity;
import com.nocobase.wiki.KnowledgeBaseService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PlaybookService 单元测试(PHASE 55 Stage 5 对标补齐 — Mattermost Playbook)。
 *
 * <p>覆盖:定义解析 fail-closed(非法 JSON 抛 400,禁静默 fallback)、
 * workflow 编译缓存复用、SLA due_at、Checklist 勾选与越界、SLA 超时升级、
 * 复盘页生成与无知识库时跳过。
 */
class PlaybookServiceTest {

    private static final String TENANT = "tenant_default";
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private PlaybookRepository playbookRepo;
    private PlaybookRunRepository runRepo;
    private WorkflowRepository workflowRepo;
    private WorkflowInstanceRepository instanceRepo;
    private WorkflowEngine workflowEngine;
    private MessageService messageService;
    private WikiPageService wikiPageService;
    private KnowledgeBaseService knowledgeBaseService;
    private ObjectMapper objectMapper;
    private PlaybookService service;

    @BeforeEach
    void setUp() {
        playbookRepo = mock(PlaybookRepository.class);
        runRepo = mock(PlaybookRunRepository.class);
        workflowRepo = mock(WorkflowRepository.class);
        instanceRepo = mock(WorkflowInstanceRepository.class);
        workflowEngine = mock(WorkflowEngine.class);
        messageService = mock(MessageService.class);
        wikiPageService = mock(WikiPageService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        objectMapper = new ObjectMapper();

        // save 回传原对象(JPA save 语义)
        when(playbookRepo.save(any(PlaybookEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(runRepo.save(any(PlaybookRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepo.save(any(WorkflowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepo.save(any(WorkflowInstanceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(runRepo.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new PlaybookService(playbookRepo, runRepo, workflowRepo, instanceRepo,
                workflowEngine, messageService, wikiPageService, knowledgeBaseService, objectMapper);
    }

    // ==================== 定义解析(fail-closed) ====================

    @Test
    void create_objectDefinition_savesDraft() {
        String def = "{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[],\"checklist\":[{\"title\":\"确认影响\"}]}";
        PlaybookEntity p = service.create(TENANT, CHANNEL, "故障响应", "desc", def, USER.toString());
        assertThat(p.getStatus()).isEqualTo(PlaybookEntity.Status.DRAFT);
        assertThat(p.getTenantId()).isEqualTo(TENANT);
        assertThat(p.getChannelId()).isEqualTo(CHANNEL);
        verify(playbookRepo).save(any(PlaybookEntity.class));
    }

    @Test
    void create_arrayDefinition_savesDraft() {
        String def = "[{\"id\":\"n1\",\"type\":\"action\"}]";
        PlaybookEntity p = service.create(TENANT, CHANNEL, "上线检查", null, def, USER.toString());
        assertThat(p.getStatus()).isEqualTo(PlaybookEntity.Status.DRAFT);
    }

    @Test
    void create_blankDefinition_throws400() {
        assertThatThrownBy(() -> service.create(TENANT, CHANNEL, "x", null, "   ", USER.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_invalidJson_throws400_noSilentFallback() {
        // 关键:非法定义必须拒绝,不得静默生成假节点
        assertThatThrownBy(() -> service.create(TENANT, CHANNEL, "x", null, "{not valid json", USER.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(playbookRepo, never()).save(any(PlaybookEntity.class));
    }

    // ==================== 定义变更 → 编译缓存失效 ====================

    @Test
    void update_newDefinition_invalidatesWorkflowCache() {
        PlaybookEntity existing = new PlaybookEntity();
        existing.setId(UUID.randomUUID());
        existing.setYamlSource("[{\"id\":\"n1\"}]");
        existing.setWorkflowId(UUID.randomUUID()); // 已有编译缓存
        when(playbookRepo.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.update(existing.getId(), "new name", null, "[{\"id\":\"n2\"}]");

        assertThat(existing.getWorkflowId()).isNull(); // 缓存失效
        assertThat(existing.getName()).isEqualTo("new name");
    }

    @Test
    void activate_setsActiveStatus() {
        PlaybookEntity existing = new PlaybookEntity();
        existing.setId(UUID.randomUUID());
        when(playbookRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        service.activate(existing.getId());
        assertThat(existing.getStatus()).isEqualTo(PlaybookEntity.Status.ACTIVE);
    }

    @Test
    void archive_setsArchivedStatus() {
        PlaybookEntity existing = new PlaybookEntity();
        existing.setId(UUID.randomUUID());
        when(playbookRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        service.archive(existing.getId());
        assertThat(existing.getStatus()).isEqualTo(PlaybookEntity.Status.ARCHIVED);
    }

    @Test
    void get_notFound_throws404() {
        UUID missing = UUID.randomUUID();
        when(playbookRepo.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(missing))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 运行 ====================

    @Test
    void runOnce_arrayDefinition_usesSequentialExecution() {
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        PlaybookRunEntity run = service.runOnce(pb.getId(), USER, null);
        // edges 为空 → 顺序模式(executeFrom),非图模式
        verify(workflowEngine).executeFrom(any(WorkflowInstanceEntity.class), any(), anyInt(), eq(USER));
        verify(workflowEngine, never()).executeGraphFrom(any(), any(), any(), anyString(), any());
        assertThat(run.getStatus()).isEqualTo(PlaybookRunEntity.RUNNING);
    }

    @Test
    void runOnce_objectDefinitionWithEdges_usesGraphExecution() {
        String def = "{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[{\"source\":\"n1\",\"target\":\"n2\"}]}";
        PlaybookEntity pb = playbook(def);
        service.runOnce(pb.getId(), USER, null);
        verify(workflowEngine).executeGraphFrom(any(WorkflowInstanceEntity.class), any(), any(),
                eq("n1"), eq(USER));
    }

    @Test
    void runOnce_defaultSla_is24Hours() {
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        Instant before = Instant.now();
        PlaybookRunEntity run = service.runOnce(pb.getId(), USER, null);
        long hours = (run.getDueAt().getEpochSecond() - before.getEpochSecond()) / 3600;
        assertThat(hours).isBetween(23L, 24L); // 默认 24h
    }

    @Test
    void runOnce_customSlaHours_isRespected() {
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        Instant before = Instant.now();
        PlaybookRunEntity run = service.runOnce(pb.getId(), USER, 2);
        long hours = (run.getDueAt().getEpochSecond() - before.getEpochSecond()) / 3600;
        assertThat(hours).isBetween(1L, 2L);
    }

    @Test
    void runOnce_compilesWorkflowOnce_thenReusesCache() {
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        service.runOnce(pb.getId(), USER, null);
        UUID cached = pb.getWorkflowId();
        assertThat(cached).isNotNull();
        verify(workflowRepo, times(1)).save(any(WorkflowEntity.class)); // 仅首次编译

        // 第二次运行:缓存命中,不再重复编译
        when(workflowRepo.findById(cached)).thenReturn(Optional.of(new WorkflowEntity()));
        service.runOnce(pb.getId(), USER, null);
        verify(workflowRepo, times(1)).save(any(WorkflowEntity.class)); // 仍未新增
    }

    @Test
    void runOnce_initializesChecklistFromDefinition() {
        String def = "{\"nodes\":[{\"id\":\"n1\"}],\"checklist\":[{\"title\":\"止血\"},{\"title\":\"复盘\"}]}";
        PlaybookEntity pb = playbook(def);
        PlaybookRunEntity run = service.runOnce(pb.getId(), USER, null);
        assertThat(run.getChecklistJson()).contains("止血").contains("复盘").contains("\"done\":false");
    }

    // ==================== Checklist ====================

    @Test
    void updateChecklist_validIndex_marksDone() {
        PlaybookRunEntity run = runWithChecklist("[{\"title\":\"a\",\"done\":false},{\"title\":\"b\",\"done\":false}]");
        service.updateChecklist(run.getId(), 1, true);
        assertThat(run.getChecklistJson()).contains("\"done\":true");
    }

    @Test
    void updateChecklist_outOfRange_throws400() {
        PlaybookRunEntity run = runWithChecklist("[{\"title\":\"a\",\"done\":false}]");
        assertThatThrownBy(() -> service.updateChecklist(run.getId(), 5, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateChecklist_negativeIndex_throws400() {
        PlaybookRunEntity run = runWithChecklist("[{\"title\":\"a\",\"done\":false}]");
        assertThatThrownBy(() -> service.updateChecklist(run.getId(), -1, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== SLA 超时升级 ====================

    @Test
    void markOverdue_marksOverdueAndAppendsEvent() {
        PlaybookRunEntity r = new PlaybookRunEntity();
        r.setId(UUID.randomUUID());
        r.setStatus(PlaybookRunEntity.RUNNING);
        r.setStartedAt(Instant.now());
        when(runRepo.findOverdue(eq(TENANT), any(Instant.class))).thenReturn(List.of(r));

        List<PlaybookRunEntity> out = service.markOverdue(TENANT);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getStatus()).isEqualTo(PlaybookRunEntity.OVERDUE);
        assertThat(out.get(0).getEventsJson()).contains("sla_overdue");
    }

    // ==================== 完成 + 复盘页 ====================

    @Test
    void finishRun_withKnowledgeBase_generatesRetrospectivePage() {
        PlaybookRunEntity run = runWithChecklist("[{\"title\":\"a\",\"done\":true}]");
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        run.setPlaybookId(pb.getId());
        run.setTenantId(TENANT);

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(UUID.randomUUID());
        when(knowledgeBaseService.list(TENANT)).thenReturn(List.of(kb));
        WikiPageEntity page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        when(wikiPageService.create(any(), any(), anyString(), anyString(), anyString(),
                any(), anyString())).thenReturn(page);

        PlaybookRunEntity out = service.finishRun(run.getId(), USER);

        assertThat(out.getStatus()).isEqualTo(PlaybookRunEntity.FINISHED);
        assertThat(out.getRetrospectivePageId()).isEqualTo(page.getId());
    }

    @Test
    void finishRun_withoutKnowledgeBase_skipsRetrospectiveButStillFinishes() {
        PlaybookRunEntity run = runWithChecklist("[]");
        PlaybookEntity pb = playbook("[{\"id\":\"n1\"}]");
        run.setPlaybookId(pb.getId());
        run.setTenantId(TENANT);
        when(knowledgeBaseService.list(TENANT)).thenReturn(List.of());

        PlaybookRunEntity out = service.finishRun(run.getId(), USER);

        assertThat(out.getStatus()).isEqualTo(PlaybookRunEntity.FINISHED);
        assertThat(out.getRetrospectivePageId()).isNull();
        assertThat(out.getEventsJson()).contains("retrospective_skipped");
    }

    @Test
    void finishRun_alreadyFinished_isIdempotent() {
        PlaybookRunEntity run = runWithChecklist("[]");
        run.setStatus(PlaybookRunEntity.FINISHED);
        PlaybookRunEntity out = service.finishRun(run.getId(), USER);
        assertThat(out.getStatus()).isEqualTo(PlaybookRunEntity.FINISHED);
        verify(knowledgeBaseService, never()).list(anyString());
    }

    // ==================== helpers ====================

    private PlaybookEntity playbook(String yamlSource) {
        PlaybookEntity p = new PlaybookEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(TENANT);
        p.setChannelId(CHANNEL);
        p.setName("剧本");
        p.setYamlSource(yamlSource);
        p.setStatus(PlaybookEntity.Status.ACTIVE);
        when(playbookRepo.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private PlaybookRunEntity runWithChecklist(String checklistJson) {
        PlaybookRunEntity run = new PlaybookRunEntity();
        run.setId(UUID.randomUUID());
        run.setStatus(PlaybookRunEntity.RUNNING);
        run.setChecklistJson(checklistJson);
        run.setStartedAt(Instant.now().minusSeconds(60));
        run.setTenantId(TENANT);
        when(runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        return run;
    }
}