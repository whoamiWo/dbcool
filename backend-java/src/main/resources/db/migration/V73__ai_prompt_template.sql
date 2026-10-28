-- C4: AI 提示词模板表
CREATE TABLE IF NOT EXISTS ai_prompt_template (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    prompt_template TEXT         NOT NULL,
    variables       JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

-- 租户维度查询索引
CREATE INDEX IF NOT EXISTS idx_ai_prompt_template_tenant ON ai_prompt_template (tenant_id);
-- 名称搜索索引
CREATE INDEX IF NOT EXISTS idx_ai_prompt_template_name ON ai_prompt_template (tenant_id, name);
