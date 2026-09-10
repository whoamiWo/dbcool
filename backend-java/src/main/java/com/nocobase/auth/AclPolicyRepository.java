package com.nocobase.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AclPolicyRepository extends JpaRepository<AclPolicyEntity, UUID> {
    List<AclPolicyEntity> findByRoleIdAndTenantId(UUID roleId, String tenantId);
    List<AclPolicyEntity> findByTenantId(String tenantId);
    Optional<AclPolicyEntity> findByIdAndTenantId(UUID id, String tenantId);
}
