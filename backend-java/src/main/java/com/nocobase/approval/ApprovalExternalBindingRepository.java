package com.nocobase.approval;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalExternalBindingRepository extends JpaRepository<ApprovalExternalBindingEntity, UUID> {
    Optional<ApprovalExternalBindingEntity> findByCorpIdAndProcessCodeAndProcessInstanceId(
            String corpId, String processCode, String processInstanceId);
    Optional<ApprovalExternalBindingEntity> findByTenantIdAndRequestId(String tenantId, UUID requestId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ApprovalExternalBindingEntity b where b.id=:id")
    Optional<ApprovalExternalBindingEntity> findLockedById(@Param("id") UUID id);
}
