package com.nocobase.integration.datasource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PHASE B B3: 外部数据源仓储。
 *
 * <p>跨租户隔离由方法签名天然保证（tenantId 参与 where 条件），
 * 不存在"先查再比对"的越权窗口。
 */
public interface ExternalDataSourceRepository extends JpaRepository<ExternalDataSourceEntity, UUID> {

    List<ExternalDataSourceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ExternalDataSourceEntity> findByTenantIdAndName(String tenantId, String name);

    Optional<ExternalDataSourceEntity> findByIdAndTenantId(UUID id, String tenantId);

    boolean existsByTenantIdAndName(String tenantId, String name);
}
