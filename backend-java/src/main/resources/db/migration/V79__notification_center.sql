-- ============================================================
--  V79__notification_center.sql
--  企业自用闭环 E3（通知中心）。任务书 §T4.1。
--
--  D2：通知中心建在现有 messages 表上（加列），/api/messages 保持兼容。
--  偏好复用 user_preferences（不新建表）。
-- ============================================================

ALTER TABLE messages ADD COLUMN IF NOT EXISTS deeplink     VARCHAR(512);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS read_at      TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS digest_key   VARCHAR(128);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS priority     VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS channel_sent JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS digest_count INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN messages.deeplink     IS '点击跳转路径（如 /approvals/{id}）';
COMMENT ON COLUMN messages.digest_key   IS '聚合键：同 key 10 分钟内合并为一条并累加 digest_count';
COMMENT ON COLUMN messages.channel_sent IS '外部渠道投递结果 {channelType: success/detail}';

-- 旧值收敛 → 新类型（幂等）
UPDATE messages SET type = 'WORKFLOW_FAILED' WHERE type IN ('workflow', 'workflow_failed');
UPDATE messages SET type = 'SYSTEM'          WHERE type IN ('system');
UPDATE messages SET type = 'MENTION'         WHERE type IN ('mention');

CREATE INDEX IF NOT EXISTS idx_messages_inbox
    ON messages (tenant_id, recipient, read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_digest
    ON messages (tenant_id, recipient, digest_key, created_at DESC)
    WHERE digest_key IS NOT NULL;
