package com.nocobase.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 套餐层级仓储 (Plan Tier Repository).
 */
@Repository
public interface PlanTierRepository extends JpaRepository<PlanTierEntity, String> {

    Optional<PlanTierEntity> findBySlug(String slug);

    List<PlanTierEntity> findByIsActiveTrueOrderBySortOrderAsc();

    List<PlanTierEntity> findAllByOrderBySortOrderAsc();
}