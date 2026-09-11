-- ============================================================
--  V8__platform.sql
--  Epic 6 平台基础
-- ============================================================

-- 1. messages 表(US-505 站内信,Week 12 升级工作流通知)
CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sender UUID,                       -- NULL 表示系统消息
    recipient UUID NOT NULL,
    type VARCHAR(32) NOT NULL,        -- 'workflow' | 'system' | 'mention'
    title VARCHAR(255) NOT NULL,
    body TEXT,
    related_id VARCHAR(64),           -- 关联业务 ID(如 workflow_instance_id)
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id VARCHAR(64) NOT NULL
);
CREATE INDEX idx_messages_recipient ON messages(recipient);
CREATE INDEX idx_messages_recipient_read ON messages(recipient, read);

COMMENT ON TABLE messages IS '站内信(US-505)';

-- 2. user_preferences 表(用户偏好,留扩展)
CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
