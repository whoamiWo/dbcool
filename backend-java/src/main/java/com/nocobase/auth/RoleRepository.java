package com.nocobase.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    List<RoleEntity> findByTenantIdOrderByCreatedAt(String tenantId);

    Optional<RoleEntity> findByIdAndTenantId(UUID id, String tenantId);

    Optional<RoleEntity> findByNameAndTenantId(String name, String tenantId);

    boolean existsByNameAndTenantId(String name, String tenantId);

    List<RoleEntity> findByParentRoleId(UUID parentRoleId);

    @Query("""
            SELECT r.name FROM RoleEntity r
            JOIN UserRoleEntity ur ON ur.id.roleId = r.id
            WHERE ur.id.userId = :userId AND r.tenantId = :tenantId
            """)
    List<String> findRoleNamesByUserId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);

    /**
     * 递归查找一个角色及其所有祖先的 id — 沿 parent_role_id 链向上。
     * 实现: CTE(Common Table Expression) PostgreSQL 原生支持。
     * 返回列表包含 role 自身 + 父 + 祖父 + ...
     */
    @Query(value = """
            WITH RECURSIVE ancestors(id, parent_role_id, depth) AS (
                SELECT id, parent_role_id, 0 FROM roles WHERE id = :roleId AND tenant_id = :tenantId
                UNION ALL
                SELECT r.id, r.parent_role_id, a.depth + 1
                FROM roles r
                JOIN ancestors a ON r.id = a.parent_role_id
                WHERE r.tenant_id = :tenantId AND a.depth < 10  -- 防意外死循环
            )
            SELECT id FROM ancestors
            """, nativeQuery = true)
    List<UUID> findSelfAndAncestors(@Param("roleId") UUID roleId, @Param("tenantId") String tenantId);

    /**
     * 递归查询 user 的所有角色名(自身 + 所有祖先) — 通过两步 SQL 实现继承语义:
     *   1) 直接查询 user 的 role 名字 + parent_role_id 链
     *   2) 在 Java 层展开,确保返回去重后的名字集合
     *
     * 适合 ROW ACL principal_type=role 的策略匹配。
     */
    @Query(value = """
            WITH RECURSIVE role_chain(id, parent_role_id, depth) AS (
                SELECT r.id, r.parent_role_id, 0
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND r.tenant_id = :tenantId
                UNION ALL
                SELECT parent.id, parent.parent_role_id, rc.depth + 1
                FROM role_chain rc
                JOIN roles parent ON parent.id = rc.parent_role_id
                WHERE parent.tenant_id = :tenantId AND rc.depth < 10
            )
            SELECT r.name FROM role_chain rc2
            JOIN roles r ON r.id = rc2.id
            WHERE r.tenant_id = :tenantId
            GROUP BY r.name
            """, nativeQuery = true)
    List<String> findInheritedRoleNamesByUserId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);
}

