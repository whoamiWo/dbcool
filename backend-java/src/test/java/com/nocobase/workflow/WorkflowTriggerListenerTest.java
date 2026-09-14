package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.event.RecordChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WorkflowTriggerListener 单元测试(Week 41 D4a).
 *
 * <p>验证事件接收 → 匹配 → 引擎执行,异常隔离不传播给主流程。
 */
class WorkflowTriggerListenerTest {

    private WorkflowTriggerMatcher matcher;
    private WorkflowEngine engine;
    private WorkflowRepository repository;
    private TriggerRateLimiter rateLimiter;
    private WorkflowTriggerListener listener;

    @BeforeEach
    void setUp() {
        matcher = mock(WorkflowTriggerMatcher.class);
        engine = mock(WorkflowEngine.class);
        repository = mock(WorkflowRepository.class);
        rateLimiter = mock(TriggerRateLimiter.class);
        // 默认允许触发,死循环测试单独 mock
        org.mockito.Mockito.when(rateLimiter.allowTrigger(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        listener = new WorkflowTriggerListener(matcher, engine, repository, rateLimiter);
    }

    private WorkflowEntity workflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("approval");
        w.setTitle("Approval");
        w.setCollectionName("orders");
        w.setTenantId("tenant_default");
        w.setTriggerJson("{\"type\":\"on_create\"}");
        w.setEnabled(true);
        w.setCreatedBy(UUID.randomUUID());
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        w.setCreatedAt(Instant.now());
        return w;
    }

    private RecordChangeEvent event() {
        return new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE,
                "orders",
                UUID.randomUUID().toString(),
                Map.of("customer", "alice"),
                "tenant_default",
                UUID.randomUUID());
    }

    @Test
    void onRecordChange_noMatchingWorkflows_noEngineCall() {
        when(matcher.findMatching(any())).thenReturn(List.of());
        listener.onRecordChange(event());
        verify(engine, never()).executeGraphFrom(any(), anyList(), anyList(), anyString(), any());
        verify(engine, never()).executeFrom(any(), anyList(), eq(0), any());
    }

    @Test
    void onRecordChange_oneMatch_executesEngine() {
        when(matcher.findMatching(any())).thenReturn(List.of(workflow()));
        when(engine.executeGraphFrom(any(), anyList(), anyList(), anyString(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        listener.onRecordChange(event());

        // executeGraphFrom 因为 edges=[] 不调,fallback executeFrom
        verify(engine, times(1)).executeFrom(any(), anyList(), eq(0), any());
    }

    @Test
    void onRecordChange_matcherThrows_doesNotPropagate() {
        // 监听器异常不应导致 CollectionController 响应失败
        when(matcher.findMatching(any())).thenThrow(new RuntimeException("boom"));

        // 不应抛
        listener.onRecordChange(event());
        verify(engine, never()).executeFrom(any(), anyList(), anyInt(), any());
    }

    @Test
    void onRecordChange_engineThrows_doesNotPropagate() {
        when(matcher.findMatching(any())).thenReturn(List.of(workflow()));
        org.mockito.Mockito.doThrow(new RuntimeException("engine boom"))
                .when(engine).executeFrom(any(), anyList(), anyInt(), any());

        // 不应抛
        listener.onRecordChange(event());
    }

    @Test
    void onRecordChange_workflowWithEdges_usesGraphExecution() {
        WorkflowEntity w = workflow();
        w.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"NOTIFICATION\"}]");
        when(matcher.findMatching(any())).thenReturn(List.of(w));
        when(engine.executeGraphFrom(any(), anyList(), anyList(), anyString(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        listener.onRecordChange(event());

        verify(engine, times(1)).executeGraphFrom(any(), anyList(), anyList(), eq("n1"), any());
    }

    @Test
    void onRecordChange_rateLimitedWorkflow_skipsEngine() {
        // 死循环防护:rateLimiter 拒绝 → 跳过
        when(matcher.findMatching(any())).thenReturn(List.of(workflow()));
        org.mockito.Mockito.when(rateLimiter.allowTrigger(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        listener.onRecordChange(event());

        verify(engine, never()).executeFrom(any(), anyList(), anyInt(), any());
        verify(engine, never()).executeGraphFrom(any(), anyList(), anyList(), anyString(), any());
    }

    @Test
    void toJson_convertsEventCorrectly() {
        // 通过反射验证私有方法,或通过 public path 间接验证
        when(matcher.findMatching(any())).thenReturn(List.of(workflow()));
        when(engine.executeFrom(any(), anyList(), anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        listener.onRecordChange(event());

        // 引擎收到的 triggerData 应包含 event 关键字段
        org.mockito.ArgumentCaptor<WorkflowInstanceEntity> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowInstanceEntity.class);
        verify(engine).executeFrom(captor.capture(), anyList(), anyInt(), any());
        WorkflowInstanceEntity instance = captor.getValue();
        assertThat(instance.getRecordId()).isNotNull();
        assertThat(instance.getTenantId()).isEqualTo("tenant_default");
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.RUNNING);
        // triggerDataJson 必含 changeType
        assertThat(instance.getTriggerDataJson()).contains("CREATE");
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
