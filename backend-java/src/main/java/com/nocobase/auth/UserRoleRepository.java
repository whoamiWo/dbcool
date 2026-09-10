package com.nocobase.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleEntity.UserRoleId> {
    List<UserRoleEntity> findByIdUserId(UUID userId);
    List<UserRoleEntity> findByIdRoleId(UUID roleId);
    void deleteByIdUserId(UUID userId);
    void deleteByIdRoleId(UUID roleId);
}
