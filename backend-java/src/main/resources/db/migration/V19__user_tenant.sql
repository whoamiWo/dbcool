-- V19__user_tenant.sql
CREATE TABLE IF NOT EXISTS user_tenant (
    id        VARCHAR(64) PRIMARY KEY,
    user_id   VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(user_id, tenant_id)
);
