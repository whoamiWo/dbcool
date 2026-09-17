package com.nocobase.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserTenantRepository extends JpaRepository<UserTenantEntity, String> {
    List<UserTenantEntity> findByUserId(String userId);
    List<UserTenantEntity> findByTenantId(String tenantId);
    boolean existsByUserIdAndTenantId(String userId, String tenantId);
    void deleteByUserIdAndTenantId(String userId, String tenantId);
}
