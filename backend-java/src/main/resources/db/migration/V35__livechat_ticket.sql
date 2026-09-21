-- V35__livechat_ticket.sql
-- 工单表：Livechat 会话结束自动创建

CREATE TABLE IF NOT EXISTS im_ticket (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    customer_name VARCHAR(128) NOT NULL,
    customer_email VARCHAR(256) NOT NULL,
    message TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    agent_notes TEXT
);

COMMENT ON TABLE im_ticket IS 'Livechat 工单表 (V35)';
CREATE INDEX IF NOT EXISTS idx_im_ticket_tenant ON im_ticket(tenant_id);
CREATE INDEX IF NOT EXISTS idx_im_ticket_status ON im_ticket(status);
CREATE INDEX IF NOT EXISTS idx_im_ticket_session ON im_ticket(session_id);
