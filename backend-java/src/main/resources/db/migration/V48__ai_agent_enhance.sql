-- ============================================================
--  V48__ai_agent_enhance.sql
--  Add missing columns to ai_agent table
-- ============================================================

ALTER TABLE ai_agent ADD COLUMN IF NOT EXISTS channel_id UUID;
ALTER TABLE ai_agent ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE ai_agent ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_ai_agent_channel ON ai_agent(channel_id);
