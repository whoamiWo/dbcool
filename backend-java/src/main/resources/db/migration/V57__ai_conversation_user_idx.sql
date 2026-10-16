-- ============================================================
--  PHASE111：为 AI 会话的按用户定位加索引
--
--  背景：`UserDataErasureService#eraseAiConversations` 需要按
--  (tenant_id, user_id) 定位并匿名化某用户的全部会话（GDPR 删除权）。
--  既有索引只有 idx_ai_conv_agent(agent_id) 与
--  idx_ai_conv_channel(tenant_id, channel_id)，**没有任何含 user_id 的索引**，
--  匿名化与残留校验会全表扫。
--
--  注意：
--   1. 生产 ddl-auto=validate，索引只能通过 Flyway 加，
--      在实体上写 @Table(indexes=...) 会导致启动校验失败。
--   2. 不修改已应用的 V26（Flyway validate-on-migrate=true 会报 checksum 不符）。
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_ai_conv_user
    ON ai_conversation(tenant_id, user_id);
