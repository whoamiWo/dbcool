package com.nocobase.automation.repository;

import com.nocobase.automation.entity.AutomationRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AutomationRuleRepository extends JpaRepository<AutomationRuleEntity, UUID> {
    List<AutomationRuleEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
