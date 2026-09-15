package com.nocobase.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookSubscriptionRepository
        extends JpaRepository<WebhookSubscriptionEntity, UUID> {

    /** 事件发生时按 (租户, collection, 事件) 精确匹配启用的订阅。 */
    List<WebhookSubscriptionEntity> findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
            String tenantId, String collectionName, String event);

    List<WebhookSubscriptionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
