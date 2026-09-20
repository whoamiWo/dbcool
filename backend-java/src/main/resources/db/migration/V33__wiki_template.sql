-- V33__wiki_template.sql
-- Wiki 页面模板库

CREATE TABLE IF NOT EXISTS wiki_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    name VARCHAR(256) NOT NULL,
    title VARCHAR(256) NOT NULL,
    content_json JSONB NOT NULL DEFAULT '[]',  -- Block 树 JSON
    icon VARCHAR(64),
    tenant_id VARCHAR(64) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wiki_template_kb ON wiki_template(kb_id);
CREATE INDEX IF NOT EXISTS idx_wiki_template_tenant ON wiki_template(tenant_id);

COMMENT ON TABLE wiki_template IS 'Wiki 页面模板';
