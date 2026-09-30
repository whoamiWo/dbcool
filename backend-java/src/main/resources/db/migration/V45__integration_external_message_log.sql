-- V45: Third-party inbound message deduplication table
-- Stores processed external message IDs to prevent infinite loops and duplicate processing

CREATE TABLE IF NOT EXISTS integration_external_message_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(64) NOT NULL,              -- 'slack', 'mattermost', 'feishu', 'wecom', etc.
    external_message_id VARCHAR(256) NOT NULL, -- External message ID (Slack ts/event_id, Mattermost post_id, etc.)
    tenant_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(source, external_message_id)
);

CREATE INDEX IF NOT EXISTS idx_integration_ext_msg_lookup ON integration_external_message_log(source, external_message_id);
CREATE INDEX IF NOT EXISTS idx_integration_ext_msg_tenant ON integration_external_message_log(tenant_id);
