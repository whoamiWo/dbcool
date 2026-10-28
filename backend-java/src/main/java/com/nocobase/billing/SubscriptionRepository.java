package com.nocobase.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 订阅仓储 (Subscription Repository).
 */
@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, String> {

    /** 查询租户当前生效的订阅 (active 或 trialing)。 */
    Optional<SubscriptionEntity> findByTenantIdAndStatusIn(
            String tenantId, List<SubscriptionEntity.Status> statuses);

    List<SubscriptionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** 扫描指定租户到期需续费的订阅（定时作业按租户遍历，避免无租户条件的全表扫描）。 */
    List<SubscriptionEntity> findByTenantIdAndStatusAndCurrentPeriodEndLessThanEqual(
            String tenantId, SubscriptionEntity.Status status, LocalDate date);

    List<SubscriptionEntity> findByStatusAndNextBillingDateLessThanEqual(
            SubscriptionEntity.Status status, LocalDate date);
}