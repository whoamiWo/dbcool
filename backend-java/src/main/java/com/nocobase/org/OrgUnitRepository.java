package com.nocobase.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrgUnitRepository extends JpaRepository<OrgUnitEntity, UUID> {

    List<OrgUnitEntity> findByTenantIdAndDeletedFalseOrderBySortAscNameAsc(String tenantId);

    List<OrgUnitEntity> findByTenantIdAndParentIdAndDeletedFalseOrderBySortAscNameAsc(
            String tenantId, UUID parentId);

    Optional<OrgUnitEntity> findByIdAndTenantIdAndDeletedFalse(UUID id, String tenantId);

    Optional<OrgUnitEntity> findByTenantIdAndSourceAndExternalIdAndDeletedFalse(
            String tenantId, String source, String externalId);

    /** 子树（含自身）：path 前缀匹配。 */
    List<OrgUnitEntity> findByTenantIdAndPathStartingWithAndDeletedFalse(
            String tenantId, String pathPrefix);
}
