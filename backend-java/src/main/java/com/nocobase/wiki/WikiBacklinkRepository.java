package com.nocobase.wiki;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Wiki 双向链接 Repository — 支持正向/反向查询。
 */
@Repository
public interface WikiBacklinkRepository extends JpaRepository<WikiBacklinkEntity, UUID> {

    List<WikiBacklinkEntity> findBySourcePageId(UUID sourcePageId);

    List<WikiBacklinkEntity> findByTargetPageId(UUID targetPageId);

    List<WikiBacklinkEntity> findBySourcePageIdAndTenantId(UUID sourcePageId, String tenantId);

    List<WikiBacklinkEntity> findByTargetPageIdAndTenantId(UUID targetPageId, String tenantId);

    @Query(value = """
        SELECT b FROM WikiBacklinkEntity b
        WHERE b.targetPageId = :targetPageId
        ORDER BY b.createdAt DESC
        """)
    List<WikiBacklinkEntity> findByTargetPageIdOrderByCreatedAtDesc(@Param("targetPageId") UUID targetPageId);

    void deleteBySourcePageId(UUID sourcePageId);
}