package com.nocobase.meta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CollectionRepository extends JpaRepository<CollectionMetaEntity, UUID> {
    Optional<CollectionMetaEntity> findByName(String name);

    List<CollectionMetaEntity> findByTenantId(String tenantId);

    boolean existsByName(String name);

    /** Week 41 B3:删除 collection 元数据(配合 CollectionService.deleteMeta 调用)。 */
    long deleteByName(String name);
}
