package com.nocobase.ldap.repository;

import com.nocobase.ldap.entity.LdapUserMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LdapUserMappingRepository extends JpaRepository<LdapUserMappingEntity, UUID> {
    Optional<LdapUserMappingEntity> findByLdapUidAndTenantId(String ldapUid, String tenantId);
    List<LdapUserMappingEntity> findByTenantId(String tenantId);
}
