package com.nocobase.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 看板列 Repository。
 */
@Repository
public interface BoardListRepository extends JpaRepository<BoardListEntity, UUID> {

    /** 项目下的看板列（按排序）。 */
    List<BoardListEntity> findByTenantIdAndProjectIdOrderBySortOrderAsc(String tenantId, UUID projectId);

    /** 按类型筛选。 */
    List<BoardListEntity> findByTenantIdAndProjectIdAndType(String tenantId, UUID projectId, String type);
}