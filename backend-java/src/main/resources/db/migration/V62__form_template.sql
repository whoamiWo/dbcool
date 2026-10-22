-- V62__form_template.sql
-- US-107: 表单模板库

CREATE TABLE IF NOT EXISTS form_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    collection_name VARCHAR(64),
    schema_json JSONB NOT NULL DEFAULT '{}',
    tenant_id VARCHAR(64) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_form_template_tenant ON form_templates(tenant_id DESC);
CREATE INDEX IF NOT EXISTS idx_form_template_collection ON form_templates(collection_name, tenant_id);

COMMENT ON TABLE form_templates IS '表单模板：存储可复用的表单定义快照 (layout + rules)';
COMMENT ON COLUMN form_templates.schema_json IS '{"collectionName": "...", "layout": [...], "rules": {...}}';