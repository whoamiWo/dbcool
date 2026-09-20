package com.nocobase.wiki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Wiki 模板 Repository — 支持按知识库、租户查询。
 */
@Repository
public interface WikiTemplateRepository extends JpaRepository<WikiTemplateEntity, UUID> {

    List<WikiTemplateEntity> findByKbId(UUID kbId);

    List<WikiTemplateEntity> findByTenantId(String tenantId);

    Optional<WikiTemplateEntity> findByIdAndTenantId(UUID id, String tenantId);

    @Query(value = """
        SELECT t FROM WikiTemplateEntity t
        WHERE t.kbId = :kbId AND t.tenantId = :tenantId
        ORDER BY t.updatedAt DESC
        """)
    List<WikiTemplateEntity> findByKbIdAndTenantIdOrderByUpdatedAtDesc(
            @Param("kbId") UUID kbId, @Param("tenantId") String tenantId);
}