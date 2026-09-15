-- ============================================================
--  V15__webhook_subscriptions.sql
--  Week 41 复核 D5.3:Webhook 出口订阅
--
--  背景:此前只有"通知渠道"(管理员手工配置的通知目标),
--  没有真正的"数据变更 → 外部 URL 推送"订阅机制。
--  本表登记订阅关系,由 WebhookSubscriptionListener 在
--  RecordChangeEvent 时按 (tenant, collection, event) 匹配并推送。
-- ============================================================

CREATE TABLE webhook_subscriptions (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(128) NOT NULL,
    event           VARCHAR(32)  NOT NULL,
    target_url      VARCHAR(1024) NOT NULL,
    secret          VARCHAR(255),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 匹配订阅时的主查询路径
CREATE INDEX idx_webhook_sub_lookup
    ON webhook_subscriptions(tenant_id, collection_name, event);

CREATE INDEX idx_webhook_sub_tenant ON webhook_subscriptions(tenant_id);
