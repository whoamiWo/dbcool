package com.nocobase.ldap.repository;

import com.nocobase.ldap.entity.LdapConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LdapConfigRepository extends JpaRepository<LdapConfigEntity, UUID> {
    List<LdapConfigEntity> findByTenantId(String tenantId);
}
