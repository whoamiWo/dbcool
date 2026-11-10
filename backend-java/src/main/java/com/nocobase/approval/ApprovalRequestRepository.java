package com.nocobase.approval;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 仅一个调用方可把待外推占位为 PUSHING；HTTP 在该事务提交后执行。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE approval_request
               SET external_push_status='PUSHING', external_push_error=NULL
             WHERE id=:requestId AND status='PENDING' AND external_push_status='PENDING'
            """, nativeQuery = true)
    int claimPush(@Param("requestId") UUID requestId);
}
