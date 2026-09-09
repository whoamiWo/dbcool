package com.nocobase.form;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormRepository extends JpaRepository<FormEntity, UUID> {
    List<FormEntity> findByCollectionNameAndTenantId(String collectionName, String tenantId);
    List<FormEntity> findByTenantId(String tenantId);
    Optional<FormEntity> findByIdAndTenantId(UUID id, String tenantId);
}
