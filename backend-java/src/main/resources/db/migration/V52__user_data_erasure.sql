-- V52: 用户数据导入/导出/删除支持
CREATE TABLE IF NOT EXISTS user_data_erasure_log (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    requester_id VARCHAR(64),
    anonymous_id VARCHAR(64),
    messages_erased INTEGER DEFAULT 0,
    conversations_erased INTEGER DEFAULT 0,
    audit_logs_anonymized INTEGER DEFAULT 0,
    requested_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_data_erasure_user ON user_data_erasure_log(user_id);