package com.nocobase.integration.market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantIntegrationInstallRepository extends JpaRepository<TenantIntegrationInstallEntity, UUID> {

    /**
     * 按租户查询所有已安装的集成。
     */
    List<TenantIntegrationInstallEntity> findByTenantId(String tenantId);

    /**
     * 按租户和应用 Key 查询安装记录。
     */
    Optional<TenantIntegrationInstallEntity> findByTenantIdAndAppKey(String tenantId, String appKey);

    /**
     * 检查是否已安装。
     */
    boolean existsByTenantIdAndAppKey(String tenantId, String appKey);
}
