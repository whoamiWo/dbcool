package com.nocobase.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalTemplateRepository extends JpaRepository<ApprovalTemplateEntity, UUID> {
    List<ApprovalTemplateEntity> findByTenantIdAndEnabledTrueOrderByCreatedAtDesc(String tenantId);
    Optional<ApprovalTemplateEntity> findByIdAndTenantId(UUID id, String tenantId);
}
