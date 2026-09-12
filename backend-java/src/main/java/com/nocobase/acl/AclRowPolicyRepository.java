package com.nocobase.acl;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AclRowPolicyRepository extends JpaRepository<AclRowPolicyEntity, UUID> {

    @Query("""
            SELECT p FROM AclRowPolicyEntity p
            WHERE p.tenantId = :tenantId
              AND p.collection = :collection
              AND p.action = :action
              AND p.enabled = true
            ORDER BY p.priority DESC, p.createdAt ASC
            """)
    List<AclRowPolicyEntity> findApplicable(
            @Param("tenantId") String tenantId,
            @Param("collection") String collection,
            @Param("action") String action);

    List<AclRowPolicyEntity> findByTenantIdAndCollectionOrderByPriorityDescCreatedAtAsc(
            String tenantId, String collection);

    void deleteByTenantIdAndCollection(String tenantId, String collection);
}