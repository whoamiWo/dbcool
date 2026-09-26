package com.nocobase.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowTaskRepository extends JpaRepository<WorkflowTaskEntity, UUID> {
    List<WorkflowTaskEntity> findByInstanceId(UUID instanceId);

    /**
     * 按租户 + 办理人查询(多租户隔离)。
     *
     * <p><b>必须使用本方法替代 {@code findByAssigneeAndStatus}</b>:后者不带租户条件,
     * 用户经 UserTenantEntity 切换到其他租户后仍会查到原租户的待办(跨租户越权)。
     */
    List<WorkflowTaskEntity> findByTenantIdAndAssigneeAndStatus(
            String tenantId, UUID assignee, WorkflowTaskEntity.Status status);

    /**
     * 按办理人查询(<b>不带租户过滤</b>)。
     *
     * <p>仅用于租户无关的内部场景;对外接口请用
     * {@link #findByTenantIdAndAssigneeAndStatus}。
     */
    List<WorkflowTaskEntity> findByAssigneeAndStatus(UUID assignee, WorkflowTaskEntity.Status status);
    Optional<WorkflowTaskEntity> findByIdAndInstanceId(UUID id, UUID instanceId);
    List<WorkflowTaskEntity> findByInstanceIdAndStatus(UUID instanceId, WorkflowTaskEntity.Status status);

    /**
     * 同一实例、同一节点下仍处于 PENDING 的任务(Week 41 复核 D4b.5 会签)。
     *
     * <p>用于判断"该审批节点是否还有其他人未审批" ——
     * 只要还有 PENDING 兄弟任务,工作流就不应继续推进。
     */
    List<WorkflowTaskEntity> findByInstanceIdAndNodeIdAndStatus(
            UUID instanceId, String nodeId, WorkflowTaskEntity.Status status);
}
