package com.nocobase.approval;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalCcRepository extends JpaRepository<ApprovalCcEntity, ApprovalCcEntity.ApprovalCcId> {
    List<ApprovalCcEntity> findByRequestId(UUID requestId);
    List<ApprovalCcEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
