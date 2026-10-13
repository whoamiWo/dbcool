package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkflowTaskEntityTest {

    @Test
    void settersAndGetters_workCorrectly() {
        UUID id = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(id);
        task.setInstanceId(instanceId);
        task.setTenantId("tenant-1");
        task.setNodeId("node-1");
        task.setNodeType("approval");
        task.setAssignee(assignee);
        task.setStatus(WorkflowTaskEntity.Status.APPROVED);
        task.setComment("Approved");
        task.setCreatedAt(Instant.now());
        task.setFinishedAt(Instant.now());
        task.setTitle("Task Title");
        task.setDueAt(Instant.now());
        task.setSortOrder(1);

        assertEquals(id, task.getId());
        assertEquals(instanceId, task.getInstanceId());
        assertEquals("tenant-1", task.getTenantId());
        assertEquals("node-1", task.getNodeId());
        assertEquals("approval", task.getNodeType());
        assertEquals(assignee, task.getAssignee());
        assertEquals(WorkflowTaskEntity.Status.APPROVED, task.getStatus());
        assertEquals("Approved", task.getComment());
        assertEquals("Task Title", task.getTitle());
        assertEquals(1, task.getSortOrder());
    }

    @Test
    void defaultStatus_isPending() {
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        assertEquals(WorkflowTaskEntity.Status.PENDING, task.getStatus());
    }

    @Test
    void defaultSortOrder_isZero() {
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        assertEquals(0, task.getSortOrder());
    }

    @Test
    void statusEnum_values() {
        assertArrayEquals(
                new Object[]{
                        WorkflowTaskEntity.Status.PENDING,
                        WorkflowTaskEntity.Status.APPROVED,
                        WorkflowTaskEntity.Status.REJECTED,
                        WorkflowTaskEntity.Status.COMPLETED
                },
                WorkflowTaskEntity.Status.values()
        );
    }
}
