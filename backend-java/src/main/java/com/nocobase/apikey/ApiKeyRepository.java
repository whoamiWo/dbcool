package com.nocobase.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    /** 通过 keyPrefix 查所有匹配记录 — 用于 O(8) 范围筛选避免全表 hash 比较。
     *  真正的匹配在 service 层做 hash 比较(防止 prefix collision 误判)。 */
    List<ApiKeyEntity> findByKeyPrefixAndTenantIdAndRevokedAtIsNull(String keyPrefix, String tenantId);

    /** 通过 hash 查(主键查 — O(1) if no collision)。 */
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    /** 列出某租户的所有未撤销 key(管理 UI 用)。 */
    List<ApiKeyEntity> findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(String tenantId);
}
