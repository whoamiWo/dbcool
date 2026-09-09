-- ============================================================
--  V5__views.sql
--  视图元数据(Week 9 Epic 3)
--  type: 'table' | 'kanban' | 'detail'
--  config: 类型相关的配置(JSONB)
-- ============================================================

CREATE TABLE views (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    collection_name VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    title VARCHAR(128),
    type VARCHAR(16) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    shared_with JSONB NOT NULL DEFAULT '[]'::jsonb,  -- ['role:admin', 'user:xxx', ...]
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ
);

CREATE INDEX idx_views_collection ON views(collection_name);
CREATE INDEX idx_views_tenant ON views(tenant_id);
CREATE INDEX idx_views_type ON views(type);

COMMENT ON TABLE views IS '视图元数据(Week 9 Epic 3)';
