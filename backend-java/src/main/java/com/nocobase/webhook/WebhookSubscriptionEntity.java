package com.nocobase.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhook 出口订阅(Week 41 复核 D5.3)。
 *
 * <p>登记"某个 collection 上发生某类事件时,推送到哪个外部 URL"。
 * 与 {@code notification_channel} 的区别:后者是通知渠道配置,
 * 本表是真正的数据变更订阅。
 */
@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscriptionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "collection_name", nullable = false, length = 128)
    private String collectionName;

    /** 订阅事件:on_create / on_update / on_delete。 */
    @Column(name = "event", nullable = false, length = 32)
    private String event;

    @Column(name = "target_url", nullable = false, length = 1024)
    private String targetUrl;

    /** HMAC-SHA256 签名密钥(可空 = 不签名)。 */
    @Column(name = "secret", length = 255)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WebhookSubscriptionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
