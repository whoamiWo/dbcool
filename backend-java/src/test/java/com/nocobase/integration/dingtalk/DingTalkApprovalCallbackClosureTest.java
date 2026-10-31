package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave4 待办池 #10：钉钉审批回调业务闭环（事件消费端真实推进）。
 *
 * 链路：钉钉回调事件 → WorkflowTaskService.@EventListener → 绑定反查 →
 *       approveTask/rejectTask（与平台内审批同一逻辑）→ 任务与实例状态更新。
 */
class DingTalkApprovalCallbackClosureTest {

    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private WorkflowTaskService taskService;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        taskService = new WorkflowTaskService(taskRepository, instanceRepository, workflowRepository,
                mock(com.nocobase.workflow.WorkflowEngine.class),
                mock(com.nocobase.audit.AuditService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private WorkflowInstanceEntity instance() {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setTenantId("tenant_default");
        i.setWorkflowId(UUID.randomUUID());
        i.setStatus(WorkflowInstanceEntity.Status.PENDING);
        i.setCurrentNodeIndex(0);
        i.setTriggerDataJson("{\"dingtalkInstanceId\":\"dt-inst-1\"}");
        return i;
    }

    private WorkflowTaskEntity pendingTask(UUID instanceId) {
        WorkflowTaskEntity t = new WorkflowTaskEntity();
        t.setId(UUID.randomUUID());
        t.setInstanceId(instanceId);
        t.setTenantId("tenant_default");
        t.setStatus(WorkflowTaskEntity.Status.PENDING);
        return t;
    }

    private WorkflowEntity workflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("hook-flow");
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        return w;
    }

    /** 补齐推进链所需的 findById stub（Mockito 默认返回 null → 404）。 */
    private void stubChain(WorkflowInstanceEntity instance, WorkflowTaskEntity task) {
        when(instanceRepository.findByTriggerDataJsonContaining("dt-inst-1")).thenReturn(List.of(instance));
        when(taskRepository.findByInstanceId(instance.getId())).thenReturn(List.of(task));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(instanceRepository.findById(instance.getId())).thenReturn(Optional.of(instance));
        WorkflowEntity wf = workflow();
        when(workflowRepository.findById(wf.getId())).thenReturn(Optional.of(wf));
        // 实例的 workflowId 指向该 workflow
        instance.setWorkflowId(wf.getId());
    }

    @Test
    @DisplayName("回调 agree：任务 APPROVED，无后续节点则实例 COMPLETED")
    void callback_agree_marksTaskApprovedAndCompletesInstance() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        stubChain(instance, task);

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.APPROVED);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
        verify(taskRepository).save(task);
        verify(instanceRepository, org.mockito.Mockito.atLeastOnce()).save(instance);
    }

    @Test
    @DisplayName("回调 reject：任务 REJECTED，实例 FAILED 并带原因")
    void callback_reject_marksTaskRejectedAndFailsInstance() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        stubChain(instance, task);

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", false, "reject");

        assertThat(handled).isTrue();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.REJECTED);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.FAILED);
    }

    @Test
    @DisplayName("会签：存在其他 PENDING 任务时实例保持 PENDING（等待会签）")
    void callback_withCoSign_pendingStaysPending() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        task.setNodeId("n1");
        WorkflowTaskEntity sibling = pendingTask(instance.getId());
        sibling.setNodeId("n1");
        stubChain(instance, task);
        when(taskRepository.findByInstanceIdAndNodeIdAndStatus(any(), any(), any()))
                .thenReturn(List.of(sibling));

        taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.APPROVED);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.PENDING);
    }

    @Test
    @DisplayName("无绑定：返回 false 且不误改任何任务")
    void callback_withoutBinding_returnsFalse() {
        when(instanceRepository.findByTriggerDataJsonContaining(any())).thenReturn(List.of());

        boolean handled = taskService.approveByExternalBinding("dt-unknown", true, "agree");

        assertThat(handled).isFalse();
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Wave4#10 收尾：图模式工作流 approve 后经引擎图遍历续跑下游节点")
    void callback_graphMode_continuesViaEngine() {
        WorkflowInstanceEntity instance = instance();
        instance.setCurrentNodeIndex(0);
        WorkflowTaskEntity task = pendingTask(instance.getId());
        task.setNodeId("n1");
        stubChain(instance, task);
        // 工作流带 edges → 图模式
        WorkflowEntity wf = workflow();
        wf.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"},{\"id\":\"n2\",\"type\":\"NOTIFICATION\"}]");
        wf.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        // 重新 stub workflow（stubChain 里的 workflow() 不带 edges）—— 实例指向带 edges 的工作流
        instance.setWorkflowId(wf.getId());
        when(workflowRepository.findById(wf.getId())).thenReturn(java.util.Optional.of(wf));
        var engine = (com.nocobase.workflow.WorkflowEngine)
                org.springframework.test.util.ReflectionTestUtils.getField(taskService, "engine");
        when(engine.executeGraphFrom(any(), any(), any(), org.mockito.ArgumentMatchers.eq("n2"), any()))
                .thenReturn(com.nocobase.workflow.WorkflowEngine.NodeResult.CONTINUE);

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        // 图模式：引擎图遍历被调用且起点是下一个节点 n2
        verify(engine).executeGraphFrom(any(), any(), any(), org.mockito.ArgumentMatchers.eq("n2"), any());
    }

    @Test
    @DisplayName("Wave4#10 收尾：数组模式（无 edges）不走引擎图遍历（行为向后兼容）")
    void callback_arrayMode_doesNotInvokeGraphEngine() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        stubChain(instance, task); // workflow() 无 edges
        var engine = (com.nocobase.workflow.WorkflowEngine)
                org.springframework.test.util.ReflectionTestUtils.getField(taskService, "engine");

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never())
                .executeGraphFrom(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Wave4#10 收尾：图模式当前已是最后节点 → 实例 COMPLETED（覆盖 nextIdx 越界分支）")
    void graphMode_lastNode_completes() {
        WorkflowInstanceEntity instance = instance();
        instance.setCurrentNodeIndex(1); // n1(0) 已过，n2(1) 是最后一个
        WorkflowTaskEntity task = pendingTask(instance.getId());
        task.setNodeId("n2");
        stubChain(instance, task);
        WorkflowEntity wf = workflow();
        wf.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"},{\"id\":\"n2\",\"type\":\"NOTIFICATION\"}]");
        wf.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        instance.setWorkflowId(wf.getId());
        when(workflowRepository.findById(wf.getId())).thenReturn(java.util.Optional.of(wf));

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
        verify(instanceRepository, org.mockito.Mockito.atLeastOnce()).save(instance);
    }

    @Test
    @DisplayName("Wave4#10 收尾：edgesJson 非法 JSON → 降级数组模式（覆盖解析失败分支）")
    void graphMode_badEdgesJson_fallsBackToArrayMode() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        stubChain(instance, task);
        WorkflowEntity wf = workflow();
        wf.setNodesJson("[]");
        wf.setEdgesJson("{invalid json");
        instance.setWorkflowId(wf.getId());
        when(workflowRepository.findById(wf.getId())).thenReturn(java.util.Optional.of(wf));
        var engine = (com.nocobase.workflow.WorkflowEngine)
                org.springframework.test.util.ReflectionTestUtils.getField(taskService, "engine");

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        // 降级为空节点数组推进 → 无下游 → 实例 COMPLETED，且不调用引擎
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never())
                .executeGraphFrom(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Wave4#10 收尾：nodesJson 非法 JSON → 图模式空节点 → 实例 COMPLETED（覆盖解析失败分支）")
    void graphMode_badNodesJson_completes() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        stubChain(instance, task);
        WorkflowEntity wf = workflow();
        wf.setNodesJson("[broken");
        wf.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        instance.setWorkflowId(wf.getId());
        when(workflowRepository.findById(wf.getId())).thenReturn(java.util.Optional.of(wf));

        boolean handled = taskService.approveByExternalBinding("dt-inst-1", true, "agree");

        assertThat(handled).isTrue();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
    }}
