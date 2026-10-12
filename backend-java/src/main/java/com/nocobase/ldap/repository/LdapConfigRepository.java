package com.nocobase.ldap.repository;

import com.nocobase.ldap.entity.LdapConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LdapConfigRepository extends JpaRepository<LdapConfigEntity, UUID> {
    List<LdapConfigEntity> findByTenantId(String tenantId);
    Optional<LdapConfigEntity> findByIdAndTenantId(UUID id, String tenantId);

    /** PHASE96 T6: 定时同步改为遍历所有租户的启用配置，不再硬编码 system 租户。 */
    List<LdapConfigEntity> findByEnabledTrue();
}
