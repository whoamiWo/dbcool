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
}
