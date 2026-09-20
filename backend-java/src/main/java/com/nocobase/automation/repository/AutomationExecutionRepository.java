package com.nocobase.automation.repository;

import com.nocobase.automation.entity.AutomationExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AutomationExecutionRepository extends JpaRepository<AutomationExecutionEntity, UUID> {
    List<AutomationExecutionEntity> findByRuleIdOrderByCreatedAtDesc(UUID ruleId);
}
