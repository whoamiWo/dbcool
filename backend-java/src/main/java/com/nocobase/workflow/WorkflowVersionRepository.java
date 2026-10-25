package com.nocobase.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, UUID> {

    List<WorkflowVersionEntity> findByWorkflowIdAndTenantIdOrderByVersionDesc(
            UUID workflowId, String tenantId);

    Optional<WorkflowVersionEntity> findByWorkflowIdAndVersionAndTenantId(
            UUID workflowId, Integer version, String tenantId);

    /** 取某工作流当前最大版本号（无记录则返回 0）。 */
    @Query(
            "SELECT COALESCE(MAX(v.version), 0) FROM WorkflowVersionEntity v WHERE v.workflowId = :workflowId AND v.tenantId = :tenantId")
    int findMaxVersionByWorkflowIdAndTenantId(
            @Param("workflowId") UUID workflowId,
            @Param("tenantId") String tenantId);
}
