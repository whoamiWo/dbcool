-- ============================================================
--  V3__migration_jobs.sql
--  Schema 迁移任务表(US-005 修改表)
-- ============================================================

CREATE TABLE migration_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    collection_name VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,  -- 'add_field' | 'drop_field' | 'rename_field' | 'alter_type'
    payload JSONB NOT NULL,           -- 操作详情(字段名、新类型等)
    status VARCHAR(16) NOT NULL,      -- 'pending' | 'running' | 'completed' | 'failed' | 'cancelled'
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_by UUID
);

CREATE INDEX idx_migration_jobs_tenant ON migration_jobs(tenant_id);
CREATE INDEX idx_migration_jobs_status ON migration_jobs(status);
CREATE INDEX idx_migration_jobs_collection ON migration_jobs(collection_name);

COMMENT ON TABLE migration_jobs IS 'Collection schema 迁移任务(US-005)';
