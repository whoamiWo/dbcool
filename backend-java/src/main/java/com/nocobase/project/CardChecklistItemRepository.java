package com.nocobase.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CardChecklistItemRepository extends JpaRepository<CardChecklistItemEntity, UUID> {

    /** 清单项（按排序）。 */
    List<CardChecklistItemEntity> findByTenantIdAndChecklistIdOrderBySortOrderAsc(String tenantId, UUID checklistId);
}