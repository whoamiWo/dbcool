-- ============================================================
--  V26__ai_agent.sql
--  Sovereign AI Agent：频道虚拟成员 + 多步工具执行
-- ============================================================

-- 1. AI Agent 定义
CREATE TABLE IF NOT EXISTS ai_agent (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    avatar VARCHAR(255),
    system_prompt TEXT,
    tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    llm_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_agent_tenant ON ai_agent(tenant_id, enabled);

-- 2. AI 会话（上下文）
CREATE TABLE IF NOT EXISTS ai_conversation (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    agent_id UUID NOT NULL REFERENCES ai_agent(id) ON DELETE CASCADE,
    channel_id UUID REFERENCES im_channel(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    messages JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_conv_agent ON ai_conversation(agent_id);
CREATE INDEX IF NOT EXISTS idx_ai_conv_channel ON ai_conversation(tenant_id, channel_id);

COMMENT ON TABLE ai_agent IS 'AI Agent 定义（频道虚拟成员）';
COMMENT ON TABLE ai_conversation IS 'AI 会话上下文（多轮对话）';