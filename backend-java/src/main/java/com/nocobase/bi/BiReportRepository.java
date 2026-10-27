package com.nocobase.bi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface BiReportRepository extends JpaRepository<BiReportEntity, UUID> {
    List<BiReportEntity> findByTenantIdAndCollectionName(String tenantId, String collectionName);
    List<BiReportEntity> findByTenantId(String tenantId);

    /** PHASE96 T4: 报表详情的租户校验查询（跨租户/不存在一律 404）。 */
    java.util.Optional<BiReportEntity> findByIdAndTenantId(UUID id, String tenantId);

    /** Wave3 B7: 查询所有启用定时任务的报表。 */
    List<BiReportEntity> findByScheduleEnabledTrue();
}