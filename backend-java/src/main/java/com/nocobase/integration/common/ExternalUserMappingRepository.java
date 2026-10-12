package com.nocobase.integration.common;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** PHASE96 T1: 外部用户映射仓储。 */
@Repository
public interface ExternalUserMappingRepository extends JpaRepository<ExternalUserMappingEntity, UUID> {

    Optional<ExternalUserMappingEntity> findByPlatformAndExternalUserIdAndTenantId(
            String platform, String externalUserId, String tenantId);
}
