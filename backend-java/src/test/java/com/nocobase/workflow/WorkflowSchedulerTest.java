package com.nocobase.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WorkflowScheduler 单元测试(Week 41 复核 D4a — 定时触发)。
 *
 * <p>覆盖:schedule 配置解析(有效 / 非 schedule / 缺间隔 / 非法 JSON)、
 * 禁用工作流跳过、间隔未到不重复触发、引擎异常不冒泡。
 */
class WorkflowSchedulerTest {

    private WorkflowRepository workflowRepository;
    private WorkflowEngine engine;
    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        engine = mock(WorkflowEngine.class);
        when(engine.executeFrom(any(), any(), anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);
        when(engine.executeGraphFrom(any(), any(), any(), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);
        scheduler = new WorkflowScheduler(workflowRepository, engine);
    }

    @Test
    void poll_noWorkflows_doesNothing() {
        when(workflowRepository.findAll()).thenReturn(List.of());
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_disabledWorkflow_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(false, "{\"type\":\"schedule\",\"intervalMinutes\":60}")));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_nonScheduleTrigger_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"on_create\"}")));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_scheduleMissingInterval_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"schedule\"}")));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_invalidTriggerJson_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(workflow(true, "not-json")));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_blankTriggerJson_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(workflow(true, "  ")));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_dueSchedule_triggersOnce() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}")));
        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_withinInterval_doesNotRetrigger() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}")));
        scheduler.pollScheduledWorkflows();
        scheduler.pollScheduledWorkflows(); // 第二次:未到间隔,不应再触发
        verify(engine, times(1)).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_engineThrows_doesNotPropagate() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":1}")));
        when(engine.executeFrom(any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("boom"));
        // 调度器内部 catch,异常不应冒泡出去
        scheduler.pollScheduledWorkflows();
    }

    @Test
    void poll_graphWorkflow_usesGraphExecution() {
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}");
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"NOTIFICATION\"}]");
        w.setEdgesJson("[{\"source\":\"n1\",\"target\":\"end\"}]");
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeGraphFrom(any(), any(), any(), eq("n1"), any());
    }

    private WorkflowEntity workflow(boolean enabled, String triggerJson) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("sched");
        w.setEnabled(enabled);
        w.setTriggerJson(triggerJson);
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        w.setTenantId("tenant_default");
        w.setCreatedBy(UUID.randomUUID());
        return w;
    }
}
