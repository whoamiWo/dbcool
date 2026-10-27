package com.nocobase.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/** 数据质量规则 Repository。 */
public interface DataQualityRuleRepository extends JpaRepository<DataQualityRuleEntity, UUID> {

    List<DataQualityRuleEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DataQualityRuleEntity> findByTenantIdAndCollectionNameAndEnabledTrue(
            String tenantId, String collectionName);
}
