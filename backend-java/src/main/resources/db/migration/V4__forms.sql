-- ============================================================
--  V4__forms.sql
--  Form(表单)元数据 — Epic 2 表单设计器(Week 8)
-- ============================================================

CREATE TABLE forms (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    collection_name VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    layout JSONB NOT NULL DEFAULT '[]'::jsonb,  -- FormLayout:字段数组 + 顺序 + 分组
    rules JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 显隐规则 + 校验规则 + 提交动作
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ
);

CREATE INDEX idx_forms_collection ON forms(collection_name);
CREATE INDEX idx_forms_tenant ON forms(tenant_id);

COMMENT ON TABLE forms IS '表单元数据(Week 8 Epic 2)';
