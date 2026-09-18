package com.nocobase.bi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface BiReportRepository extends JpaRepository<BiReportEntity, UUID> {
    List<BiReportEntity> findByTenantIdAndCollectionName(String tenantId, String collectionName);
    List<BiReportEntity> findByTenantId(String tenantId);
}