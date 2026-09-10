package com.nocobase.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {
    List<RoleEntity> findByTenantIdOrderByCreatedAt(String tenantId);
    Optional<RoleEntity> findByIdAndTenantId(UUID id, String tenantId);
    Optional<RoleEntity> findByNameAndTenantId(String name, String tenantId);
    boolean existsByNameAndTenantId(String name, String tenantId);
}
