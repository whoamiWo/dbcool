-- ============================================================
--  V2__collection_meta.sql
--  Collection 元数据表 + JSONB 字段
-- ============================================================

CREATE TABLE collection_meta (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(128),
    description VARCHAR(500),
    fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID
);

CREATE INDEX idx_collection_meta_tenant ON collection_meta(tenant_id);
CREATE INDEX idx_collection_meta_name ON collection_meta(name);
