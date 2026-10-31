package com.nocobase.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, UUID> {
    List<WorkflowInstanceEntity> findByWorkflowIdAndTenantIdOrderByStartedAtDesc(UUID workflowId, String tenantId);
    List<WorkflowInstanceEntity> findByTenantIdOrderByStartedAtDesc(String tenantId);
    Optional<WorkflowInstanceEntity> findByIdAndTenantId(UUID id, String tenantId);

    /** Week 41 B2:用于删除工作流前检查活跃实例(RUNNING/PENDING)。 */
    List<WorkflowInstanceEntity> findByWorkflowIdAndStatusIn(UUID workflowId, Collection<WorkflowInstanceEntity.Status> statuses);

    /**
     * Wave4 待办池 #10：按 trigger_data_json 内容（如钉钉审批实例 ID 绑定）反查工作流实例。
     */
    @Query(value = "select * from workflow_instances where trigger_data_json like concat('%', :key, '%')",
            nativeQuery = true)
    List<com.nocobase.workflow.WorkflowInstanceEntity> findByTriggerDataJsonContaining(
            @Param("key") String key);
}