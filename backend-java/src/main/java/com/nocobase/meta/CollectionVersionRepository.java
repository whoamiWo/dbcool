package com.nocobase.meta;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 集合版本 Repository (Wave 4 C2)。
 */
@Repository
public interface CollectionVersionRepository extends JpaRepository<CollectionVersionEntity, Long> {

    /** 按 collectionId 查询所有版本，按版本号降序。 */
    List<CollectionVersionEntity> findByCollectionIdOrderByVersionDesc(UUID collectionId);

    /** 查询指定 collection 的某个版本。 */
    CollectionVersionEntity findByCollectionIdAndVersion(UUID collectionId, Integer version);

    /** 查询指定 collection 的最新版本号。 */
    @Query("select coalesce(max(v.version), 0) from CollectionVersionEntity v where v.collectionId = :cid")
    Integer findMaxVersionByCollectionId(@Param("cid") UUID collectionId);
    
    /** 检查版本是否存在。 */
    boolean existsByCollectionIdAndVersion(UUID collectionId, Integer version);
}
