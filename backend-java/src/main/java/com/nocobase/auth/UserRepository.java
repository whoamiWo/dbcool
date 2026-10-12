package com.nocobase.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByIdAndTenantId(UUID id, String tenantId);
    /** PHASE95: 数据库层按租户过滤，取代 listAll 全表扫描后内存过滤。 */
    List<UserEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
