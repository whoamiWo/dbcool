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

    /** 回调只锁定精确租户与实例，禁止通过 trigger_data 子串猜测外部身份。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkflowInstanceEntity i where i.id=:id and i.tenantId=:tenantId")
    Optional<WorkflowInstanceEntity> findLockedByIdAndTenantId(
            @Param("id") UUID id, @Param("tenantId") String tenantId);
}