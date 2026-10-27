-- V69: 数据治理中枢 — 保留策略 + 数据质量规则
-- Phase B Wave 3 B7

CREATE TABLE IF NOT EXISTS retention_policy (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    action          VARCHAR(16)  NOT NULL DEFAULT 'ARCHIVE',  -- ARCHIVE / DELETE
    retention_days  INT          NOT NULL DEFAULT 365,
    last_executed_at TIMESTAMP,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_retention_tenant ON retention_policy(tenant_id);
CREATE INDEX IF NOT EXISTS idx_retention_collection ON retention_policy(tenant_id, collection_name);

CREATE TABLE IF NOT EXISTS data_quality_rule (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64)  NOT NULL,
    field_name      VARCHAR(128),           -- 非空/唯一/正则/范围适用的字段;范围可跨多字段
    rule_type       VARCHAR(32)  NOT NULL,  -- NOT_NULL / UNIQUE / REGEX / RANGE
    rule_config     JSONB,                 -- 正则: {"pattern":"..."}; 范围: {"min":..,"max":..,"field":".."}
    severity        VARCHAR(16)  NOT NULL DEFAULT 'WARN',  -- WARN / ERROR
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_quality_tenant ON data_quality_rule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_quality_collection ON data_quality_rule(tenant_id, collection_name);
