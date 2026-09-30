-- V46: Tenant-level integration installation records
-- Tracks which integrations each tenant has installed/enabled

CREATE TABLE IF NOT EXISTS tenant_integration_install (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    app_key VARCHAR(128) NOT NULL,
    app_name VARCHAR(256) NOT NULL,
    version VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INSTALLED', -- INSTALLED, DISABLED, UNINSTALLING
    config_json JSONB NOT NULL DEFAULT '{}',
    installed_by UUID,
    installed_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, app_key)
);

CREATE INDEX IF NOT EXISTS idx_tenant_integration_tenant ON tenant_integration_install(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tenant_integration_app ON tenant_integration_install(app_key);
