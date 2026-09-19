-- ============================================================
--  V23__im_enhance.sql
--  IM 增强：Pin 消息 + 阅后即焚 + 跨频道搜索内容索引
--  依赖: V18__im_message.sql
-- ============================================================

-- 1. 置顶消息表 (Pin)
CREATE TABLE IF NOT EXISTS im_pin (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    channel_id UUID NOT NULL REFERENCES im_channel(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES im_message(id) ON DELETE CASCADE,
    pinned_by UUID NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unpinned_at TIMESTAMPTZ,
    UNIQUE(tenant_id, channel_id, message_id)
);
CREATE INDEX idx_im_pin_channel_pinned ON im_pin(tenant_id, channel_id, pinned_at DESC);

-- 2. im_message 扩展：阅后即焚 + 过期清理
ALTER TABLE im_message
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS burn_after_read BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_imessage_content_search ON im_message(tenant_id, content);
CREATE INDEX IF NOT EXISTS idx_imessage_expires ON im_message(tenant_id, expires_at) WHERE expires_at IS NOT NULL;

COMMENT ON COLUMN im_message.expires_at IS '阅后即焚过期时间（到期自动软删除）';
COMMENT ON COLUMN im_message.burn_after_read IS '阅后即焚标记';
