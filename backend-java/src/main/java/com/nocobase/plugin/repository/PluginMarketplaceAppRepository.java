package com.nocobase.plugin.repository;

import com.nocobase.plugin.entity.PluginMarketplaceAppEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PluginMarketplaceAppRepository extends JpaRepository<PluginMarketplaceAppEntity, UUID> {
    Optional<PluginMarketplaceAppEntity> findByAppKeyAndTenantId(String appKey, String tenantId);
    List<PluginMarketplaceAppEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PluginMarketplaceAppEntity> findByTenantIdAndCategory(String tenantId, String category);
    List<PluginMarketplaceAppEntity> findByTenantIdAndStatus(String tenantId, String status);
    List<PluginMarketplaceAppEntity> findByTenantIdAndStatusIn(String tenantId, List<String> statuses);

    /** 跨租户按状态查询（启动装载已安装插件用）。 */
    List<PluginMarketplaceAppEntity> findByStatus(String status);
}
