package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.notification.NotifyDispatcher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R2：失败通知统一入口（实例终态为准）。 */
class WorkflowFailureNotifierTest {

    private WorkflowRepository workflowRepository;
    private NotifyDispatcher dispatcher;
    private WorkflowFailureNotifier notifier;
    private final String tenant = "tenant_a";
    private final UUID workflowId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID fallback = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        dispatcher = mock(NotifyDispatcher.class);
        notifier = new WorkflowFailureNotifier(workflowRepository, dispatcher);
    }

    private WorkflowInstanceEntity instance(WorkflowInstanceEntity.Status status) {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setWorkflowId(workflowId);
        i.setTenantId(tenant);
        i.setStatus(status);
        i.setErrorMessage("boom");
        return i;
    }

    private WorkflowEntity workflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(workflowId);
        w.setTenantId(tenant);
        w.setName("请假流程");
        w.setCreatedBy(owner);
        return w;
    }

    @Test
    void nonFailedInstance_skips() {
        notifier.notifyIfFailed(instance(WorkflowInstanceEntity.Status.COMPLETED), workflowId,
                fallback, "wf", tenant);
        verify(dispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    void nullInstance_skips() {
        assertThatCode(() -> notifier.notifyIfFailed(null, workflowId, fallback, "wf", tenant))
                .doesNotThrowAnyException();
        verify(dispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    void failedInstance_notifiesWorkflowCreator() {
        when(workflowRepository.findByIdAndTenantId(workflowId, tenant))
                .thenReturn(Optional.of(workflow()));

        notifier.notifyIfFailed(instance(WorkflowInstanceEntity.Status.FAILED), workflowId,
                fallback, "wf", tenant);

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.WORKFLOW_FAILED), eq(owner), any());
    }

    @Test
    void workflowMissing_fallsBackToTriggerUser() {
        when(workflowRepository.findByIdAndTenantId(workflowId, tenant))
                .thenReturn(Optional.empty());

        notifier.notifyIfFailed(instance(WorkflowInstanceEntity.Status.FAILED), workflowId,
                fallback, null, tenant);

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.WORKFLOW_FAILED), eq(fallback), any());
    }

    @Test
    void noRecipient_doesNotThrow() {
        when(workflowRepository.findByIdAndTenantId(workflowId, tenant))
                .thenReturn(Optional.empty());

        assertThatCode(() -> notifier.notifyIfFailed(
                instance(WorkflowInstanceEntity.Status.FAILED), workflowId, null, null, tenant))
                .doesNotThrowAnyException();
        verify(dispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    void dispatcherFailure_isSwallowed() {
        when(workflowRepository.findByIdAndTenantId(workflowId, tenant))
                .thenReturn(Optional.of(workflow()));
        when(dispatcher.notifyOne(any(), any(), any())).thenThrow(new RuntimeException("smtp"));

        assertThatCode(() -> notifier.notifyFailure(
                instance(WorkflowInstanceEntity.Status.FAILED), workflowId, null, "wf", tenant))
                .doesNotThrowAnyException();
    }

    @Test
    void notifyFailure_sendsWithoutStatusCheck() {
        WorkflowInstanceEntity running = instance(WorkflowInstanceEntity.Status.RUNNING);
        when(workflowRepository.findByIdAndTenantId(workflowId, tenant))
                .thenReturn(Optional.of(workflow()));

        notifier.notifyFailure(running, workflowId, fallback, "wf", tenant);

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.WORKFLOW_FAILED), eq(owner), any());
    }
}
