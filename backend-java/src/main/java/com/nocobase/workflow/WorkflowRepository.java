package com.nocobase.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
    List<WorkflowEntity> findByCollectionNameAndTenantIdOrderByCreatedAtDesc(String collectionName, String tenantId);
    List<WorkflowEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<WorkflowEntity> findByIdAndTenantId(UUID id, String tenantId);
}
