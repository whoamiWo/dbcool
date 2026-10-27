package com.nocobase.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/** 保留策略 Repository。 */
public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, UUID> {

    List<RetentionPolicyEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<RetentionPolicyEntity> findByTenantIdAndCollectionName(String tenantId, String collectionName);

    List<RetentionPolicyEntity> findByEnabledTrue();
}
