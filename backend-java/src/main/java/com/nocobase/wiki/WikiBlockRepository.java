package com.nocobase.wiki;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Wiki Block Repository — 支持按页面、父块、租户查询。
 */
@Repository
public interface WikiBlockRepository extends JpaRepository<WikiBlockEntity, UUID> {

    List<WikiBlockEntity> findByPageIdOrderBySortOrderAsc(UUID pageId);

    List<WikiBlockEntity> findByParentIdOrderBySortOrderAsc(UUID parentId);

    List<WikiBlockEntity> findByPageIdAndParentIdIsNullOrderBySortOrderAsc(UUID pageId);

    @Query(value = """
        SELECT b FROM WikiBlockEntity b
        WHERE b.tenantId = :tenantId
        ORDER BY b.sortOrder ASC
        """)
    List<WikiBlockEntity> findByTenantIdOrderBySortOrderAsc(@Param("tenantId") String tenantId);

    long countByPageId(UUID pageId);

    @Modifying
    @Query("delete from WikiBlockEntity b where b.pageId = :pageId")
    void deleteByPageId(@Param("pageId") UUID pageId);
}