package com.nocobase.form;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormTemplateRepository extends JpaRepository<FormTemplateEntity, UUID> {
    List<FormTemplateEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<FormTemplateEntity> findByCollectionNameAndTenantId(String collectionName, String tenantId);
    Optional<FormTemplateEntity> findByIdAndTenantId(UUID id, String tenantId);
}