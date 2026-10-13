package com.nocobase.integration.credential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PHASE99 T1: 租户级集成凭证 Repository。
 *
 * <p>所有查询都带 {@code tenantId} —— 跨租户读由 Repository 方法签名天然保证，
 * 不存在"先查再比对"的越权窗口（PHASE95 已确立的范式）。
 */
@Repository
public interface TenantIntegrationCredentialRepository
        extends JpaRepository<TenantIntegrationCredentialEntity, UUID> {

    /** 按租户 + provider + key 精确查询（主路径） */
    Optional<TenantIntegrationCredentialEntity> findByTenantIdAndProviderAndCredentialKey(
            String tenantId, String provider, String credentialKey);

    /** 按租户 + provider 列出全部凭证（管理界面/排查） */
    List<TenantIntegrationCredentialEntity> findByTenantIdAndProvider(String tenantId, String provider);

    /** 按租户列出全部 provider（管理界面） */
    List<TenantIntegrationCredentialEntity> findByTenantId(String tenantId);

    /** 删除指定凭证（轮换/撤销） */
    void deleteByTenantIdAndProviderAndCredentialKey(String tenantId, String provider, String credentialKey);
}
