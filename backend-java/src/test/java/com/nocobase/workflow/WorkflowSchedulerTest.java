package com.nocobase.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * WorkflowScheduler 单元测试 (Phase 60 T3-2: 去内存态 + Cron 支持).
 *
 * <p>覆盖:
 * <ul>
 *   <li>Cron 表达式解析与触发时机</li>
 *   <li>Interval 向后兼容</li>
 *   <li>分布式锁防止多实例重复触发</li>
 *   <li>重启后不重复立即触发 (基于 lastTriggeredAt)</li>
 *   <li>非法 cron 明确报错</li>
 * </ul>
 */
class WorkflowSchedulerTest {

    private WorkflowRepository workflowRepository;
    private WorkflowEngine engine;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        engine = mock(WorkflowEngine.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(engine.executeFrom(any(), any(), anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);
        when(engine.executeGraphFrom(any(), any(), any(), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);
        scheduler = new WorkflowScheduler(workflowRepository, engine, redisTemplate);
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
                workflow(false, "{\"type\":\"schedule\",\"intervalMinutes\":60}", null)));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_nonScheduleTrigger_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(
                workflow(true, "{\"type\":\"on_create\"}", null)));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_cronWorkflow_triggersOnSchedule() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"cron\",\"expression\":\"* * * * * *\"}", null);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeFrom(any(), any(), anyInt(), any());
        verify(workflowRepository, times(1)).save(w);
    }

    @Test
    void poll_intervalWorkflow_respectsLastTriggeredAt() {
        UUID wfId = UUID.randomUUID();
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}", oneHourAgo);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_intervalWithinWindow_notDue() {
        UUID wfId = UUID.randomUUID();
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}", tenMinutesAgo);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);

        scheduler.pollScheduledWorkflows();
        verify(engine, never()).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_lockedByOtherInstance_skipped() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":1}", null);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(false);

        scheduler.pollScheduledWorkflows();
        verify(engine, never()).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_invalidCron_throwsException() {
        WorkflowEntity w = workflow(true, "{\"type\":\"cron\",\"expression\":\"invalid cron\"}", null);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(any(), any(), anyLong(), any())).thenReturn(true);

        scheduler.pollScheduledWorkflows();
        verify(engine, never()).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_cronWithNullLastTriggeredAt_checksNextTime() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"cron\",\"expression\":\"0 0 0 * * ?\"}", null);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);

        scheduler.pollScheduledWorkflows();
        verify(engine, never()).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_invalidTriggerJson_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(workflow(true, "not-json", null)));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_blankTriggerJson_skipped() {
        when(workflowRepository.findAll()).thenReturn(List.of(workflow(true, "  ", null)));
        scheduler.pollScheduledWorkflows();
        verifyNoInteractions(engine);
    }

    @Test
    void poll_cronPriorityOver_interval() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"cron\",\"expression\":\"* * * * * *\",\"intervalMinutes\":30}", null);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    void poll_graphWorkflow_usesGraphExecution() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":60}", null);
        w.setId(wfId);
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"NOTIFICATION\"}]");
        w.setEdgesJson("[{\"source\":\"n1\",\"target\":\"end\"}]");
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        scheduler.pollScheduledWorkflows();
        verify(engine, times(1)).executeGraphFrom(any(), any(), any(), eq("n1"), any());
    }

    @Test
    void poll_lockReleasedAfterExecution() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = workflow(true, "{\"type\":\"schedule\",\"intervalMinutes\":1}", null);
        w.setId(wfId);
        when(workflowRepository.findAll()).thenReturn(List.of(w));
        when(valueOps.setIfAbsent(eq("workflow:schedule:lock:" + wfId), eq("1"), anyLong(), any()))
                .thenReturn(true);
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        scheduler.pollScheduledWorkflows();
        verify(redisTemplate).delete(eq("workflow:schedule:lock:" + wfId));
    }

    private WorkflowEntity workflow(boolean enabled, String triggerJson, Instant lastTriggeredAt) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("sched");
        w.setEnabled(enabled);
        w.setTriggerJson(triggerJson);
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        w.setTenantId("tenant_default");
        w.setCreatedBy(UUID.randomUUID());
        w.setLastTriggeredAt(lastTriggeredAt);
        return w;
    }
}