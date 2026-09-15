package com.nocobase.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowTaskRepository extends JpaRepository<WorkflowTaskEntity, UUID> {
    List<WorkflowTaskEntity> findByInstanceId(UUID instanceId);
    List<WorkflowTaskEntity> findByAssigneeAndStatus(UUID assignee, WorkflowTaskEntity.Status status);
    Optional<WorkflowTaskEntity> findByIdAndInstanceId(UUID id, UUID instanceId);

    /**
     * 同一实例、同一节点下仍处于 PENDING 的任务(Week 41 复核 D4b.5 会签)。
     *
     * <p>用于判断"该审批节点是否还有其他人未审批" ——
     * 只要还有 PENDING 兄弟任务,工作流就不应继续推进。
     */
    List<WorkflowTaskEntity> findByInstanceIdAndNodeIdAndStatus(
            UUID instanceId, String nodeId, WorkflowTaskEntity.Status status);
}
