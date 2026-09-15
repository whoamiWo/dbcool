package com.nocobase.meta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CollectionRepository extends JpaRepository<CollectionMetaEntity, UUID> {
    Optional<CollectionMetaEntity> findByName(String name);

    /** Week 41 复核 D2:关联解析时按租户精确定位目标 collection。 */
    Optional<CollectionMetaEntity> findByNameAndTenantId(String name, String tenantId);

    List<CollectionMetaEntity> findByTenantId(String tenantId);

    boolean existsByName(String name);

    /** Week 41 B3:删除 collection 元数据(配合 CollectionService.deleteMeta 调用)。 */
    long deleteByName(String name);
}
