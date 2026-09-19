package com.nocobase.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CardChecklistRepository extends JpaRepository<CardChecklistEntity, UUID> {

    /** 卡片下的清单（按排序）。 */
    List<CardChecklistEntity> findByTenantIdAndTaskIdOrderBySortOrderAsc(String tenantId, UUID taskId);
}