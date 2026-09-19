package com.nocobase.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CardLabelRepository extends JpaRepository<CardLabelEntity, UUID> {

    /** 项目下的全部标签。 */
    List<CardLabelEntity> findByTenantIdAndProjectId(String tenantId, UUID projectId);
}