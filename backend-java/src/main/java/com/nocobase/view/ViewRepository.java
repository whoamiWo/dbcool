package com.nocobase.view;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ViewRepository extends JpaRepository<ViewEntity, UUID> {
    List<ViewEntity> findByCollectionNameAndTenantIdOrderByCreatedAtDesc(String collectionName, String tenantId);
    List<ViewEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<ViewEntity> findByIdAndTenantId(UUID id, String tenantId);
}
