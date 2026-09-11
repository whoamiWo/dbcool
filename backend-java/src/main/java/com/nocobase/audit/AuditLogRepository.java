package com.nocobase.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.tenantId = :tenantId
          AND (:resource IS NULL OR a.resource = :resource)
          AND (:action IS NULL OR a.action = :action)
          AND (:userId IS NULL OR a.userId = :userId)
        ORDER BY a.createdAt DESC
    """)
    List<AuditLogEntity> findByFilter(
            @Param("tenantId") String tenantId,
            @Param("resource") String resource,
            @Param("action") String action,
            @Param("userId") String userId,
            Pageable pageable);

    long countByTenantId(String tenantId);
}
