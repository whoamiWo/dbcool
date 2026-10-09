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

    /**
     * PHASE93 合规：批量匿名化某用户的审计日志用户名（**只改 username，保留日志本身**）。
     *
     * <p>必须用批量 UPDATE 而非逐条 save：审计日志可能有数千条，
     * 逐条 save 会让删除接口超时（实测因此返回 HTTP 0）。
     *
     * @return 受影响行数
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE AuditLogEntity a SET a.username = :anon "
            + "WHERE a.tenantId = :tenantId AND a.userId = :userId")
    int anonymizeUsername(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("anon") String anon);
}
