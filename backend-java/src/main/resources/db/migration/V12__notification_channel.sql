-- 多渠道通知 channel
CREATE TABLE notification_channel (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,           -- email / webhook / dingtalk / wechat_work
    name VARCHAR(128) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 类型相关配置
    events VARCHAR(256) DEFAULT '',      -- 触发事件类型(逗号分隔,空=全部)
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(256) DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID
);

CREATE INDEX idx_notification_channel_tenant ON notification_channel(tenant_id);
CREATE INDEX idx_notification_channel_type ON notification_channel(tenant_id, type);
CREATE INDEX idx_notification_channel_enabled ON notification_channel(tenant_id, enabled) WHERE enabled = TRUE;
