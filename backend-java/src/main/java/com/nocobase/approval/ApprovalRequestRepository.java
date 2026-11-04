package com.nocobase.approval;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {
    List<ApprovalRequestEntity> findByTenantIdAndApplicantIdOrderBySubmittedAtDesc(
            String tenantId, UUID applicantId);
    List<ApprovalRequestEntity> findByTenantIdAndStatusOrderBySubmittedAtDesc(
            String tenantId, String status);
    Optional<ApprovalRequestEntity> findByIdAndTenantId(UUID id, String tenantId);
    List<ApprovalRequestEntity> findByTenantIdAndInstanceIdIn(String tenantId, Collection<UUID> instanceIds);
    java.util.Optional<ApprovalRequestEntity> findByInstanceId(UUID instanceId);
    Optional<ApprovalRequestEntity> findByTenantIdAndExternalInstanceId(
            String tenantId, String externalInstanceId);
}
