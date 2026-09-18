-- BI 报表定义表(Week 44 — 保存数据透视/图表配置)
-- 与 project_tasks 一样落在 public schema,按 tenant_id 隔离

CREATE TABLE bi_reports (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    collection_name VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    title           VARCHAR(128),
    type            VARCHAR(16) NOT NULL DEFAULT 'PIVOT',
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by      UUID,
    updated_by      UUID,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);

CREATE INDEX idx_bi_reports_tenant_collection ON bi_reports (tenant_id, collection_name);
